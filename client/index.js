'use strict';

const FramedDuplexLink = require('./lib/FramedDuplexLink');

/**
 * Transaction handle returned by client.transaction() / adapter.
 * Encapsulates a txId so callers do not need to manually pass {txId}
 * on every query/execute. This provides a more ergonomic API and
 * centralizes the tx "wrapping" logic (deduplicating createTxHandle
 * copies that existed in consuming adapters).
 */
class Transaction {
  constructor(client, txId) {
    this.client = client;
    this.txId = txId;
  }

  query(sql, params = []) {
    return this.client.query(sql, params, { txId: this.txId });
  }

  execute(sql, params = []) {
    return this.client.execute(sql, params, { txId: this.txId });
  }

  commit() {
    return this.client.commit(this.txId);
  }

  rollback() {
    return this.client.rollback(this.txId);
  }
}

/**
 * High-level facade over one or more duplex links to the Java jt400-proxy.
 * Provides query(sql, params) and execute(sql, params) that return friendly values.
 *
 * The Node application can use this class directly, or go through the small
 * HTTP facade server (server.js) that most of the rest of the system prefers.
 */
class Jt400ProxyClient {
  constructor(options = {}) {
    this.host = options.host || process.env.JT400_PROXY_HOST || 'localhost';
    this.port = options.port || parseInt(process.env.JT400_PROXY_PORT || '9400', 10);
    this.numLinks = options.numLinks || parseInt(process.env.JT400_PROXY_NUM_LINKS || '3', 10);
    this.requestTimeoutMs = options.requestTimeoutMs || parseInt(process.env.JT400_PROXY_REQUEST_TIMEOUT_MS || '30000', 10);
    this.healthIntervalMs = options.healthIntervalMs !== undefined
      ? options.healthIntervalMs
      : parseInt(process.env.JT400_PROXY_HEALTH_INTERVAL_MS || '15000', 10);

    this.links = [];
    this.rrIndex = 0;
    this.connected = false;
    this._healthTimer = null;
    this._closed = false;
    this._reconnectAttempts = [];
  }

  async connect() {
    if (this.connected) return this;

    this._closed = false;

    if (this._pendingReconnectTimers) {
      for (const t of this._pendingReconnectTimers) clearTimeout(t);
      this._pendingReconnectTimers.clear();
    }

    if (this._healthTimer) {
      clearInterval(this._healthTimer);
      this._healthTimer = null;
    }

    this._reconnectAttempts = new Array(this.numLinks).fill(0);
    this.links = [];
    const connectPromises = [];

    for (let i = 0; i < this.numLinks; i++) {
      const link = new FramedDuplexLink({
        host: this.host,
        port: this.port,
        requestTimeoutMs: this.requestTimeoutMs,
        linkId: i
      });

      // Forward some events for observability
      link.on('error', (e) => this.emit && this.emit('link-error', { linkId: i, error: e }));
      link.on('close', () => this._onLinkClose(link, i));

      this.links.push(link);
      connectPromises.push(
        link.connect().catch(err => {
          console.error(`[Jt400ProxyClient] link ${i} initial connect failed: ${err.message}`);
          return null; // allow partial success
        })
      );
    }

    await Promise.all(connectPromises);
    const healthy = this.links.filter(l => l.connected).length;
    this.connected = healthy > 0;

    if (this.healthIntervalMs > 0) {
      this._startHealthPinger();
    }

    console.log(`[Jt400ProxyClient] connected with ${healthy}/${this.numLinks} duplex links to ${this.host}:${this.port}`);
    return this;
  }

  _onLinkClose(link, idx) {
    if (this._closed) return;
    this._scheduleReconnect(idx);
  }

  _getReconnectDelay(idx) {
    const attempt = (this._reconnectAttempts && this._reconnectAttempts[idx]) || 0;
    const baseMs = 500;
    const maxMs = 30000;
    // Exponential backoff: 0.5s, 1s, 2s, 4s, ... capped at 30s
    const backoff = Math.min(maxMs, baseMs * Math.pow(2, attempt));
    const jitter = Math.random() * Math.min(1000, backoff * 0.25); // up to 25% jitter, capped
    return Math.floor(backoff + jitter);
  }

  _scheduleReconnect(idx) {
    if (this._closed) return;

    const attempt = (this._reconnectAttempts[idx] || 0);
    const delay = this._getReconnectDelay(idx);
    // increment for next time (will be reset on success)
    if (!this._reconnectAttempts) this._reconnectAttempts = [];
    this._reconnectAttempts[idx] = attempt + 1;

    const timer = setTimeout(async () => {
      if (this._pendingReconnectTimers) {
        this._pendingReconnectTimers.delete(timer);
      }
      if (this._closed || !this.links[idx]) return;
      const link = this.links[idx];
      if (link.connected || link.connecting) return;
      try {
        await link.connect();
        console.log(`[Jt400ProxyClient] link ${idx} reconnected`);
        // success: reset backoff for this link
        if (this._reconnectAttempts) this._reconnectAttempts[idx] = 0;
      } catch (e) {
        // will retry with increased backoff on next schedule (from health or future close)
      }
    }, delay);

    if (!this._pendingReconnectTimers) this._pendingReconnectTimers = new Set();
    this._pendingReconnectTimers.add(timer);
  }

  _startHealthPinger() {
    if (this._healthTimer || !this.healthIntervalMs || this.healthIntervalMs <= 0) return;
    this._healthTimer = setInterval(() => {
      for (let i = 0; i < this.links.length; i++) {
        this._checkLinkHealth(i).catch(() => {});
      }
    }, this.healthIntervalMs);
    if (typeof this._healthTimer.unref === 'function') {
      this._healthTimer.unref();
    }
  }

  async _checkLinkHealth(i) {
    if (this._closed) return;
    const link = this.links[i];
    if (!link || !link.connected) return;
    try {
      const ok = await link.ping();
      if (!ok) throw new Error('ping returned non-success');
    } catch (e) {
      if (this._closed || !link.connected) return;
      console.warn(`[Jt400ProxyClient] link ${i} health ping failed: ${e.message}`);
      link.connected = false;
      try {
        if (link.socket) link.socket.destroy();
      } catch (_) {}
      this._scheduleReconnect(i);
    }
  }

  _pickLink() {
    if (this.links.length === 0) throw new Error('no links configured');

    // Simple round-robin among currently connected links, with fallback
    for (let i = 0; i < this.links.length; i++) {
      const idx = (this.rrIndex + i) % this.links.length;
      const l = this.links[idx];
      if (l && l.connected) {
        this.rrIndex = (idx + 1) % this.links.length;
        return l;
      }
    }

    // None connected right now — return the next one anyway (it will fail fast and trigger reconnect)
    const l = this.links[this.rrIndex % this.links.length];
    this.rrIndex = (this.rrIndex + 1) % this.links.length;
    return l;
  }

  _txOptions(options = {}) {
    return options.txId ? { txId: options.txId } : {};
  }

  /**
   * Internal request helper with transport-failure resilience:
   * - Picks a link via _pickLink
   * - On transport-like errors (not connected, timeout, socket closed, etc.),
   *   marks the link dead, schedules reconnect, and (for non-txId ops) retries once
   *   against another link.
   * - txId-bearing operations are NOT retried on a different link (tx state is
   *   pinned to the specific server-side FramedConnection that began the tx).
   */
  async _request(req) {
    let lastErr;
    const hasTxId = !!(req && req.txId);
    const maxAttempts = hasTxId ? 1 : 2;

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      const link = this._pickLink();
      try {
        const resp = await link.request(req);
        // successful transport on this link: reset its backoff counter
        const idx = this.links.indexOf(link);
        if (idx >= 0 && this._reconnectAttempts) this._reconnectAttempts[idx] = 0;
        return resp;
      } catch (e) {
        // Server/application errors (from success:false responses) are turned into
        // rejections by the link layer and always carry a sqlState property.
        // These must be surfaced directly without marking the link dead or retrying.
        if (e && 'sqlState' in e) {
          throw e;
        }

        // Transport / connectivity error: mark this link suspect and retry on another if allowed.
        lastErr = e;
        if (link) {
          link.connected = false;
          try { if (link.socket) link.socket.destroy(); } catch (_) {}
          const idx = this.links.indexOf(link);
          if (idx >= 0) this._scheduleReconnect(idx);
        }

        if (hasTxId || attempt >= maxAttempts - 1) {
          throw e;
        }
        // retry with a different link for non-txId operations
      }
    }
    throw lastErr;
  }

  /**
   * Execute a SELECT and return the array of row objects directly.
   * Throws on error (with .sqlState if available from the server).
   */
  // query and execute now support optional txId via options param (see below overloads)
  // The old signatures are kept for backward compat via the new implementations.

  async ping() {
    // Try links using _request (benefits from alternate-link retry on transport).
    // If that fails for transport reasons, fall back to explicitly probing links
    // (ping-all style) until we find one that responds.
    try {
      const resp = await this._request({ op: 'ping' });
      if (resp && resp.success) return true;
    } catch (e) {
      if (e && 'sqlState' in e) {
        // Server returned an error for ping (unusual) — treat as not ok
        return false;
      }
      // transport error on picked link(s); continue to full probe
    }

    // Explicitly probe links (ping-all behavior) until one succeeds
    const candidates = this.links.filter(l => l);
    for (const link of candidates) {
      try {
        const ok = await link.ping();
        if (ok) {
          link.connected = true;
          const idx = this.links.indexOf(link);
          if (idx >= 0 && this._reconnectAttempts) this._reconnectAttempts[idx] = 0;
          return true;
        }
      } catch (e) {
        if (link.connected) {
          link.connected = false;
          try { if (link.socket) link.socket.destroy(); } catch (_) {}
          const idx = this.links.indexOf(link);
          if (idx >= 0) this._scheduleReconnect(idx);
        }
      }
    }
    return false;
  }

  /**
   * Ping every configured link and return a detailed report.
   * This actively uses the 'ping' op on each link (bypassing simple connected flag).
   */
  async pingAll() {
    const results = [];
    for (let i = 0; i < this.links.length; i++) {
      const link = this.links[i];
      if (!link) {
        results.push({ linkId: i, ok: false, error: 'no link instance' });
        continue;
      }
      let ok = false;
      let error = null;
      try {
        ok = await link.ping();
      } catch (e) {
        error = e && e.message ? e.message : String(e);
        if (link.connected) {
          link.connected = false;
          try { if (link.socket) link.socket.destroy(); } catch (_) {}
          this._scheduleReconnect(i);
        }
      }
      if (ok && !link.connected) {
        link.connected = true;
      }
      if (ok) {
        const idx = this.links.indexOf(link);
        if (idx >= 0 && this._reconnectAttempts) this._reconnectAttempts[idx] = 0;
      }
      results.push({
        linkId: i,
        connected: !!link.connected,
        ok,
        error
      });
    }
    const anyOk = results.some(r => r.ok);
    const allOk = results.length > 0 && results.every(r => r.ok);
    return { ok: anyOk, allOk, results };
  }

  /**
   * Request current HikariCP connection pool metrics from the Java proxy.
   * Useful for monitoring (exposed via HTTP /pool-stats and /metrics).
   *
   * Response includes:
   *  - Hikari pool stats (activeConnections, idleConnections, etc.)
   *  - transactions: { parkedCount, parked: [ {txId, startTime, lastUsed, ageMs, idleMs}, ... ] }
   *    startTime = creation time of the tx (from begin-tx)
   *    lastUsed  = last activity timestamp (updated on every tx-scoped query/execute)
   *    The sweeper now uses lastUsed for timeout decisions (last-activity model).
   */
  async getPoolStats() {
    const resp = await this._request({ op: 'pool-stats' });
    if (!resp.success) {
      const err = new Error(resp.error || 'failed to get pool stats');
      err.sqlState = resp.sqlState;
      throw err;
    }
    const { id, success, op, connection, ...stats } = resp;
    return stats;
  }

  /**
   * Begin a new transaction on the Java side (parks a connection from the pool).
   * Returns a txId that must be passed to subsequent query/execute under the tx,
   * and finally to commit/rollback.
   *
   * The server-side sweeper will auto-rollback if the tx exceeds the configured
   * timeout (default 5 minutes).
   */
  async beginTransaction(options = {}) {
    const req = { op: 'begin-tx' };
    if (options.isolationLevel) req.options = { isolationLevel: options.isolationLevel };
    if (options.timeoutMs) {
      req.options = req.options || {};
      req.options.timeoutMs = options.timeoutMs;
    }
    const resp = await this._request(req);
    if (!resp.success) {
      const err = new Error(resp.error || 'failed to begin tx');
      err.sqlState = resp.sqlState;
      throw err;
    }
    return resp.txId;
  }

  /**
   * Commit a transaction by txId. The parked connection is released back to the pool.
   */
  async commit(txId) {
    if (!txId) throw new Error('txId is required');
    const resp = await this._request({ op: 'commit-tx', txId });
    if (!resp.success) {
      const err = new Error(resp.error || 'commit failed');
      err.sqlState = resp.sqlState;
      throw err;
    }
    return { txId: resp.txId, status: resp.status };
  }

  /**
   * Rollback a transaction by txId. The parked connection is released back to the pool.
   */
  async rollback(txId) {
    if (!txId) throw new Error('txId is required');
    const resp = await this._request({ op: 'rollback-tx', txId });
    if (!resp.success) {
      const err = new Error(resp.error || 'rollback failed');
      err.sqlState = resp.sqlState;
      throw err;
    }
    return { txId: resp.txId, status: resp.status };
  }

  /**
   * Begin a transaction and return a Transaction handle that bakes in the txId.
   * Subsequent calls on the handle auto-route to the parked connection.
   *
   * Supports callback style (for compat with node-jt400 pool.transaction(fn) usage
   * in legacy wrappers):
   *   client.transaction(tx => { ... use tx.query / tx.update;  tx.commit() later });
   *   // fn is invoked with the handle; for async fns we await it.
   *
   * When no callback is passed, returns the handle directly (caller is responsible
   * for commit/rollback).
   *
   * Does NOT auto-commit on callback success (legacy patterns manage commit
   * explicitly via commitDBTransaction etc).
   */
  async transaction(fn) {
    const txId = await this.beginTransaction();
    const handle = new Transaction(this, txId);
    if (typeof fn === 'function') {
      try {
        // Support both sync and async callbacks (legacy eval'd code is often sync scheduling)
        const maybePromise = fn(handle);
        if (maybePromise && typeof maybePromise.then === 'function') {
          await maybePromise;
        }
        return handle;
      } catch (e) {
        // best-effort rollback to match adapter behavior
        try { await this.rollback(txId); } catch (_) {}
        throw e;
      }
    }
    return handle;
  }

  /**
   * Query under an optional tx. If txId provided, uses the parked connection.
   */
  async query(sql, params = [], options = {}) {
    const req = { op: 'query', sql, params };
    if (options.txId) req.txId = options.txId;
    const resp = await this._request(req);
    if (!resp.success) {
      const err = new Error(resp.error || 'query failed');
      err.sqlState = resp.sqlState;
      err.durationMs = resp.durationMs;
      throw err;
    }
    return resp.data || [];
  }

  /**
   * Execute under an optional tx. If txId provided, uses the parked connection.
   */
  async execute(sql, params = [], options = {}) {
    const req = { op: 'execute', sql, params };
    if (options.txId) req.txId = options.txId;
    const resp = await this._request(req);
    if (!resp.success) {
      const err = new Error(resp.error || 'execute failed');
      err.sqlState = resp.sqlState;
      err.durationMs = resp.durationMs;
      throw err;
    }
    return { affectedRows: resp.affectedRows || 0, durationMs: resp.durationMs };
  }

  /**
   * Sequential batch execution (great for the kind of bulk workloads shown in
   * the sibling microservice's server.js + as400pool.runBatch).
   *
   * queries: array of { name?, sql, params? }
   * Returns { totalQueries, successful, failed, totalDurationMs, results: [...] }
   * Each result has name, success, durationMs, data (or error + sqlState).
   *
   * Implemented on top of this.query (row-oriented). For DML-heavy batches
   * you can still call execute individually or post-process.
   */
  async runBatch(queries = [], options = {}) {
    const results = [];
    const totalStart = process.hrtime.bigint();
    const queryOptions = this._txOptions(options);

    for (const q of queries) {
      const start = process.hrtime.bigint();
      try {
        const data = await this.query(q.sql, q.params || [], queryOptions);
        const end = process.hrtime.bigint();
        results.push({
          name: q.name || 'Unnamed',
          success: true,
          durationMs: Number(end - start) / 1e6,
          data
        });
      } catch (err) {
        const end = process.hrtime.bigint();
        results.push({
          name: q.name || 'Unnamed',
          success: false,
          error: err.message || String(err),
          sqlState: err.sqlState,
          durationMs: Number(end - start) / 1e6
        });
      }
    }

    const totalEnd = process.hrtime.bigint();
    const totalMs = Number(totalEnd - totalStart) / 1e6;

    return {
      totalQueries: results.length,
      successful: results.filter(r => r.success).length,
      failed: results.filter(r => !r.success).length,
      totalDurationMs: parseFloat(totalMs.toFixed(2)),
      results
    };
  }

  /**
   * Parallel execution with simple concurrency limiter (sibling as400pool.runParallel style).
   * Our duplex + request ID design makes overlapping requests cheap.
   */
  async runParallel(queries = [], concurrency = 10, options = {}) {
    const results = new Array(queries.length);
    const totalStart = process.hrtime.bigint();
    const queryOptions = this._txOptions(options);

    let currentIndex = 0;

    const executeNext = async () => {
      const index = currentIndex++;
      if (index >= queries.length) return;

      const q = queries[index];
      const start = process.hrtime.bigint();
      try {
        const data = await this.query(q.sql, q.params || [], queryOptions);
        const end = process.hrtime.bigint();
        results[index] = {
          name: q.name || 'Unnamed',
          success: true,
          durationMs: Number(end - start) / 1e6,
          data
        };
      } catch (err) {
        const end = process.hrtime.bigint();
        results[index] = {
          name: q.name || 'Unnamed',
          success: false,
          error: err.message || String(err),
          sqlState: err.sqlState,
          durationMs: Number(end - start) / 1e6
        };
      }

      await executeNext();
    };

    const runners = [];
    const n = Math.min(concurrency, queries.length);
    for (let i = 0; i < n; i++) {
      runners.push(executeNext());
    }
    await Promise.all(runners);

    const totalEnd = process.hrtime.bigint();
    const totalMs = Number(totalEnd - totalStart) / 1e6;
    const successful = results.filter(r => r && r.success).length;

    return {
      totalQueries: results.length,
      successful,
      failed: results.length - successful,
      totalDurationMs: parseFloat(totalMs.toFixed(2)),
      avgPerQuery: parseFloat((totalMs / Math.max(1, results.length)).toFixed(2)),
      results
    };
  }

  getStats() {
    return {
      connected: this.connected,
      links: this.links.map(l => l.getStats()),
      host: this.host,
      port: this.port
    };
  }

  close() {
    this._closed = true;

    if (this._healthTimer) {
      clearInterval(this._healthTimer);
      this._healthTimer = null;
    }

    // Cancel any scheduled reconnect attempts from previous 'close' events or health checks.
    if (this._pendingReconnectTimers) {
      for (const t of this._pendingReconnectTimers) {
        clearTimeout(t);
      }
      this._pendingReconnectTimers.clear();
    }

    // Clear the links array *before* closing the link objects.
    // This ensures that any 'close' events emitted by l.close() below
    // (or by in-flight socket closes) will see an empty array in _onLinkClose / _scheduleReconnect.
    const linksToClose = this.links;
    this.links = [];
    this.connected = false;
    this._reconnectAttempts = [];

    for (const l of linksToClose) {
      try {
        // Remove our listeners so deliberate shutdown 'close'/'error' events
        // do not trigger reconnect logic or forwarding.
        l.removeAllListeners('close');
        l.removeAllListeners('error');
        l.close();
      } catch (_) {}
    }
  }
}

// Convenience: a tiny self-test that exercises framing without needing the Java server
// (starts an in-process echo server using the same framing rules)
Jt400ProxyClient.selfTestFraming = async function selfTestFraming() {
  const Framed = require('./lib/FramedDuplexLink');
  const net = require('net');

  const server = net.createServer((sock) => {
    let buf = Buffer.alloc(0);
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      while (buf.length >= 4) {
        const len = buf.readUInt32BE(0);
        if (buf.length < 4 + len) break;
        const payload = buf.slice(4, 4 + len);
        buf = buf.slice(4 + len);
        // echo the same payload back as response (with same id if present)
        try {
          const obj = JSON.parse(payload.toString('utf8'));
          const out = Buffer.from(JSON.stringify({ id: obj.id || 'x', success: true, pong: true, echo: obj }), 'utf8');
          const h = Buffer.allocUnsafe(4);
          h.writeUInt32BE(out.length, 0);
          sock.write(Buffer.concat([h, out]));
        } catch (e) {
          // ignore bad frame in test
        }
      }
    });
  });

  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;

  const link = new Framed({ host: '127.0.0.1', port, requestTimeoutMs: 2000, linkId: 99 });
  await link.connect();
  const resp = await link.request({ op: 'ping', hello: 'world' });
  link.close();
  server.close();

  if (!resp.success || !resp.echo) throw new Error('framing self-test failed');
  return true;
};

module.exports = Jt400ProxyClient;

// === Dual-mode support ===
//
// Mode 1 (library / dependency):
//   const Jt400ProxyClient = require('jt400-proxy-client');
//   const client = new Jt400ProxyClient(...);
//
// Mode 2 (standalone HTTP server):
//   const { startServer } = require('jt400-proxy-client/facade');
//   // or simply: npx jt400-proxy-client
//
// Both factories are also available from the main entry:
const facadeModule = require('./facade');
module.exports.createFacadeApp = facadeModule.createFacadeApp;
module.exports.startServer = facadeModule.startServer;

// Expose Transaction handle for advanced use / testing / adapters
module.exports.Transaction = Transaction;

/**
 * Factory for a legacy-shaped tx handle (with .update returning affectedRows number,
 * .query, .commit, .rollback). Lets adapters avoid copy/pasting createTxHandle.
 */
module.exports.createTxHandle = function createTxHandle(client, txId) {
  const tx = new Transaction(client, txId);
  return {
    query: (sql, params = []) => tx.query(sql, params),
    update: (sql, params = []) => tx.execute(sql, params).then(r => (r && r.affectedRows) || 0),
    commit: () => tx.commit(),
    rollback: () => tx.rollback(),
  };
};
