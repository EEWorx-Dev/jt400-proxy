'use strict';

const net = require('net');
const EventEmitter = require('events');

/**
 * One persistent full-duplex TCP connection to the Java proxy.
 * Implements length-prefixed (BE uint32) + JSON framing.
 * Supports multiple in-flight requests via id correlation.
 */
class FramedDuplexLink extends EventEmitter {
  constructor(options = {}) {
    super();
    this.host = options.host || 'localhost';
    this.port = options.port || 9400;
    this.requestTimeoutMs = options.requestTimeoutMs || 30000;
    this.linkId = options.linkId || 0;

    this.socket = null;
    this.buffer = Buffer.alloc(0);
    this.pending = new Map(); // id -> { resolve, reject, timer, sentAt }
    this.connected = false;
    this.connecting = false;
    this.reqCounter = 0;
    this._onData = this._onData.bind(this);
    this._onClose = this._onClose.bind(this);
    this._onError = this._onError.bind(this);
  }

  async connect() {
    if (this.connected || this.connecting) return;
    this.connecting = true;

    return new Promise((resolve, reject) => {
      this.socket = new net.Socket();
      this.socket.setNoDelay(true);
      this.socket.setKeepAlive(true, 10000);

      const timeout = setTimeout(() => {
        this._cleanupSocket();
        this.connecting = false;
        reject(new Error(`connect timeout to ${this.host}:${this.port}`));
      }, 8000);

      this.socket.connect(this.port, this.host, () => {
        clearTimeout(timeout);
        this.connected = true;
        this.connecting = false;
        this.buffer = Buffer.alloc(0);
        this.socket.on('data', this._onData);
        this.socket.on('close', this._onClose);
        this.socket.on('error', this._onError);
        this.emit('connect');
        resolve();
      });

      this.socket.once('error', (err) => {
        clearTimeout(timeout);
        this.connecting = false;
        reject(err);
      });
    });
  }

  _onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);

    while (this.buffer.length >= 4) {
      const len = this.buffer.readUInt32BE(0);
      if (this.buffer.length < 4 + len) break;

      const payload = this.buffer.slice(4, 4 + len);
      this.buffer = this.buffer.slice(4 + len);

      let msg;
      try {
        msg = JSON.parse(payload.toString('utf8'));
      } catch (e) {
        this.emit('protocol-error', new Error('invalid JSON frame'));
        continue;
      }
      this._handleMessage(msg);
    }
  }

  _handleMessage(msg) {
    const id = msg && msg.id;
    if (!id) {
      // Could be a server-initiated message in future (e.g. stats push)
      this.emit('message', msg);
      return;
    }
    const entry = this.pending.get(id);
    if (!entry) return; // late response or unknown

    clearTimeout(entry.timer);
    this.pending.delete(id);

    if (msg.success === false) {
      const err = new Error(msg.error || 'proxy error');
      err.sqlState = msg.sqlState;
      err.durationMs = msg.durationMs;
      entry.reject(err);
    } else {
      entry.resolve(msg);
    }
  }

  _onClose() {
    this.connected = false;
    this._failAllPending(new Error('socket closed'));
    this.emit('close');
  }

  _onError(err) {
    this._failAllPending(err);
    this.emit('error', err);
  }

  _failAllPending(err) {
    for (const [id, entry] of this.pending) {
      clearTimeout(entry.timer);
      entry.reject(err);
    }
    this.pending.clear();
  }

  _cleanupSocket() {
    if (this.socket) {
      this.socket.removeListener('data', this._onData);
      this.socket.removeListener('close', this._onClose);
      this.socket.removeListener('error', this._onError);
      try { this.socket.destroy(); } catch (_) {}
      this.socket = null;
    }
    this.connected = false;
    this.connecting = false;
  }

  /**
   * Send a request and return a Promise for the response object.
   * The response contains the original fields plus 'id' and 'success'.
   */
  request(obj) {
    return new Promise((resolve, reject) => {
      if (!this.connected || !this.socket) {
        return reject(new Error('not connected'));
      }

      const id = `${this.linkId}-${++this.reqCounter}-${Date.now().toString(36)}`;
      const payload = Object.assign({ id }, obj);

      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`request ${id} timed out after ${this.requestTimeoutMs}ms`));
      }, this.requestTimeoutMs);

      this.pending.set(id, { resolve, reject, timer, sentAt: Date.now() });

      try {
        const json = Buffer.from(JSON.stringify(payload), 'utf8');
        const header = Buffer.allocUnsafe(4);
        header.writeUInt32BE(json.length, 0);
        this.socket.write(Buffer.concat([header, json]));
      } catch (e) {
        this.pending.delete(id);
        clearTimeout(timer);
        reject(e);
      }
    });
  }

  async ping() {
    const resp = await this.request({ op: 'ping' });
    return !!resp.success;
  }

  close() {
    this._failAllPending(new Error('link closed by client'));
    this._cleanupSocket();
    this.emit('close');
  }

  getStats() {
    return {
      linkId: this.linkId,
      connected: this.connected,
      pending: this.pending.size
    };
  }
}

module.exports = FramedDuplexLink;
