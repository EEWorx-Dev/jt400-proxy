'use strict';

const FramedDuplexLink = require('./lib/FramedDuplexLink');

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

    this.links = [];
    this.rrIndex = 0;
    this.connected = false;
  }

  async connect() {
    if (this.connected) return this;

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

    console.log(`[Jt400ProxyClient] connected with ${healthy}/${this.numLinks} duplex links to ${this.host}:${this.port}`);
    return this;
  }

  _onLinkClose(link, idx) {
    // Attempt background reconnect for resilience (duplex requirement)
    setTimeout(async () => {
      if (!this.links[idx]) return;
      try {
        await this.links[idx].connect();
        console.log(`[Jt400ProxyClient] link ${idx} reconnected`);
      } catch (e) {
        // will retry on next use or next timer
      }
    }, 500 + Math.random() * 1500);
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

  /**
   * Execute a SELECT and return the array of row objects directly.
   * Throws on error (with .sqlState if available from the server).
   */
  // query and execute now support optional txId via options param (see below overloads)
  // The old signatures are kept for backward compat via the new implementations.

  async ping() {
    const link = this._pickLink();
    return link.ping();
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
    const link = this._pickLink();
    const resp = await link.request({ op: 'pool-stats' });
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
    const link = this._pickLink();
    const req = { op: 'begin-tx' };
    if (options.isolationLevel) req.options = { isolationLevel: options.isolationLevel };
    if (options.timeoutMs) {
      req.options = req.options || {};
      req.options.timeoutMs = options.timeoutMs;
    }
    const resp = await link.request(req);
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
    const link = this._pickLink();
    const resp = await link.request({ op: 'commit-tx', txId });
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
    const link = this._pickLink();
    const resp = await link.request({ op: 'rollback-tx', txId });
    if (!resp.success) {
      const err = new Error(resp.error || 'rollback failed');
      err.sqlState = resp.sqlState;
      throw err;
    }
    return { txId: resp.txId, status: resp.status };
  }

  /**
   * Query under an optional tx. If txId provided, uses the parked connection.
   */
  async query(sql, params = [], options = {}) {
    const link = this._pickLink();
    const req = { op: 'query', sql, params };
    if (options.txId) req.txId = options.txId;
    const resp = await link.request(req);
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
    const link = this._pickLink();
    const req = { op: 'execute', sql, params };
    if (options.txId) req.txId = options.txId;
    const resp = await link.request(req);
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
    const queryOptions = options.txId ? { txId: options.txId } : {};

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
    const queryOptions = options.txId ? { txId: options.txId } : {};

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
    for (const l of this.links) {
      try { l.close(); } catch (_) {}
    }
    this.links = [];
    this.connected = false;
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
