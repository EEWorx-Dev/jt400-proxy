'use strict';

/**
 * HTTP Facade for jt400-proxy-client.
 *
 * Supports two modes:
 *   1. Library mode: Use Jt400ProxyClient directly.
 *   2. Standalone server mode (preferred): Expose a simple HTTP API.
 *
 * This file provides the building blocks for mode 2.
 * It is exposed as both:
 *   require('@eeworx-dev/jt400-proxy-client/facade')
 *   require('@eeworx-dev/jt400-proxy-client/server')   // alias for backward compat
 */

const express = require('express');

// Lazy require to avoid circular dependency with index.js
// (index.js re-exports startServer / createFacadeApp)
let Jt400ProxyClient;
function getClientClass() {
  if (!Jt400ProxyClient) {
    Jt400ProxyClient = require('./index');
  }
  return Jt400ProxyClient;
}

// --- Simple structured logger ---
function createLogger(level = process.env.LOG_LEVEL || 'info') {
  const levels = { error: 0, warn: 1, info: 2, debug: 3 };
  const current = levels[level.toLowerCase()] ?? 2;

  const log = (lvl, msg, meta = {}) => {
    if ((levels[lvl] ?? 2) > current) return;
    const time = new Date().toISOString();
    const base = { time, level: lvl, msg };
    console.log(JSON.stringify({ ...base, ...meta }));
  };

  return {
    error: (msg, meta) => log('error', msg, meta),
    warn: (msg, meta) => log('warn', msg, meta),
    info: (msg, meta) => log('info', msg, meta),
    debug: (msg, meta) => log('debug', msg, meta),
  };
}

/**
 * Helper to deduplicate transaction "extraction" + auto begin/commit/rollback logic.
 * Supports magic txId:'new' which creates a tx for the scope of the work,
 * commits on success, rolls back on error (and surfaces tx info).
 * Used by /batch and /parallel to avoid copy-paste.
 */
async function executeWithTx(c, rawTxId, workFn) {
  let txId = rawTxId;
  let createdTx = false;
  try {
    if (txId === 'new') {
      txId = await c.beginTransaction();
      createdTx = true;
    }

    const options = txId ? { txId } : {};
    const result = await workFn(options);

    if (createdTx) {
      await c.commit(txId);
      if (result && typeof result === 'object') {
        result.txId = txId;
        result.txStatus = 'committed';
      }
    }
    return result;
  } catch (e) {
    if (createdTx && txId) {
      try { await c.rollback(txId); } catch (_) {}
      // Attach tx info so caller can surface it in error response without duplicating
      e._txInfo = { txId, txStatus: 'rolledback' };
    }
    throw e;
  }
}

function createFacadeApp(customClient, logger = console) {
  const app = express();
  app.use(express.json({ limit: '2mb' }));

  // When no custom client is provided, create one using environment configuration.
  // This makes the standalone server (mode 2) very easy to configure via env vars.
  const Client = getClientClass();
  const c = customClient || new Client({
    host: process.env.JT400_PROXY_HOST || 'localhost',
    port: parseInt(process.env.JT400_PROXY_PORT || '9400', 10),
    numLinks: parseInt(process.env.JT400_PROXY_NUM_LINKS || '3', 10),
    requestTimeoutMs: parseInt(process.env.JT400_PROXY_REQUEST_TIMEOUT_MS || '30000', 10)
  });

  app.post('/query', async (req, res) => {
    const start = Date.now();
    try {
      const { sql, params = [], txId } = req.body || {};
      if (!sql) return res.status(400).json({ error: 'sql is required' });
      const data = await c.query(sql, params, txId ? { txId } : undefined);
      logger.debug?.('query completed', { durationMs: Date.now() - start, rowCount: data?.length ?? 0 });
      res.json({ data });
    } catch (e) {
      logger.warn?.('query failed', { error: e.message, sqlState: e.sqlState });
      res.status(500).json({
        error: e.message || 'query failed',
        sqlState: e.sqlState || null
      });
    }
  });

  app.post('/execute', async (req, res) => {
    const start = Date.now();
    try {
      const { sql, params = [], txId } = req.body || {};
      if (!sql) return res.status(400).json({ error: 'sql is required' });
      const result = await c.execute(sql, params, txId ? { txId } : undefined);
      logger.debug?.('execute completed', { durationMs: Date.now() - start, affectedRows: result?.affectedRows });
      res.json(result);
    } catch (e) {
      logger.warn?.('execute failed', { error: e.message, sqlState: e.sqlState });
      res.status(500).json({
        error: e.message || 'execute failed',
        sqlState: e.sqlState || null
      });
    }
  });

  // Batch and parallel endpoints (modeled on sibling microservice patterns + direct client methods)
  // Supports bulk workloads; results include per-query timing, success/error, and summary stats.
  app.post('/batch', async (req, res) => {
    const start = Date.now();
    const { queries = [], txId: rawTxId } = req.body || {};

    try {
      const result = await executeWithTx(c, rawTxId, (options) => c.runBatch(queries, options));

      logger.debug?.('batch completed', { durationMs: Date.now() - start, totalQueries: result.totalQueries });
      res.json(result);
    } catch (e) {
      logger.warn?.('batch failed', { error: e.message, sqlState: e.sqlState });
      const errBody = {
        error: e.message || 'batch failed',
        sqlState: e.sqlState || null
      };
      if (e._txInfo) {
        errBody.txId = e._txInfo.txId;
        errBody.txStatus = e._txInfo.txStatus;
      }
      res.status(500).json(errBody);
    }
  });

  app.post('/parallel', async (req, res) => {
    const start = Date.now();
    const { queries = [], concurrency = 10, txId: rawTxId } = req.body || {};

    try {
      const result = await executeWithTx(c, rawTxId, (options) => c.runParallel(queries, concurrency, options));

      logger.debug?.('parallel completed', { durationMs: Date.now() - start, totalQueries: result.totalQueries });
      res.json(result);
    } catch (e) {
      logger.warn?.('parallel failed', { error: e.message, sqlState: e.sqlState });
      const errBody = {
        error: e.message || 'parallel failed',
        sqlState: e.sqlState || null
      };
      if (e._txInfo) {
        errBody.txId = e._txInfo.txId;
        errBody.txStatus = e._txInfo.txStatus;
      }
      res.status(500).json(errBody);
    }
  });

  app.get('/health', async (req, res) => {
    try {
      const proxyOk = await c.ping();
      res.json({
        status: proxyOk ? 'ok' : 'degraded',
        proxy: { connected: proxyOk },
        links: c.getStats()
      });
    } catch (e) {
      res.status(500).json({ status: 'error', error: e.message });
    }
  });

  const facadeStartTime = Date.now();

  app.get('/stats', async (req, res) => {
    const clientStats = c.getStats();
    let poolStats = {};
    try {
      poolStats = await c.getPoolStats();
    } catch (e) {
      poolStats = { error: 'pool-stats unavailable: ' + e.message };
    }

    res.json({
      ...clientStats,
      pool: poolStats,
      server: {
        uptimeSeconds: Math.floor((Date.now() - facadeStartTime) / 1000),
        pid: process.pid,
      },
    });
  });

  app.get('/pool-stats', async (req, res) => {
    try {
      const pool = await c.getPoolStats();
      res.json(pool);
    } catch (e) {
      res.status(500).json({ error: 'Failed to fetch pool stats', message: e.message });
    }
  });

  // === Transaction support (basic, parks connection for duration of tx) ===
  app.post('/tx', async (req, res) => {
    try {
      const options = req.body || {};
      const txId = await c.beginTransaction(options);
      res.json({ txId, status: 'active' });
    } catch (e) {
      res.status(500).json({ error: 'Failed to begin tx', message: e.message });
    }
  });

  app.post('/tx/:txId/query', async (req, res) => {
    try {
      const { txId } = req.params;
      const { sql, params = [] } = req.body || {};
      if (!sql) return res.status(400).json({ error: 'sql is required' });
      const data = await c.query(sql, params, { txId });
      res.json({ data });
    } catch (e) {
      res.status(500).json({
        error: e.message || 'tx query failed',
        sqlState: e.sqlState || null
      });
    }
  });

  app.post('/tx/:txId/execute', async (req, res) => {
    try {
      const { txId } = req.params;
      const { sql, params = [] } = req.body || {};
      if (!sql) return res.status(400).json({ error: 'sql is required' });
      const result = await c.execute(sql, params, { txId });
      res.json(result);
    } catch (e) {
      res.status(500).json({
        error: e.message || 'tx execute failed',
        sqlState: e.sqlState || null
      });
    }
  });

  app.post('/tx/:txId/commit', async (req, res) => {
    try {
      const { txId } = req.params;
      const result = await c.commit(txId);
      res.json(result);
    } catch (e) {
      res.status(500).json({ error: 'commit failed', message: e.message });
    }
  });

  app.post('/tx/:txId/rollback', async (req, res) => {
    try {
      const { txId } = req.params;
      const result = await c.rollback(txId);
      res.json(result);
    } catch (e) {
      res.status(500).json({ error: 'rollback failed', message: e.message });
    }
  });

  // Basic error handler
  app.use((err, req, res, next) => {
    console.error('Facade error:', err);
    res.status(500).json({ error: 'internal error' });
  });

  return { app, client: c };
}

/**
 * Start the standalone HTTP server (Mode 2).
 * Returns the http.Server instance + client for external control (graceful shutdown, etc.).
 *
 * Example:
 *   const { startServer } = require('jt400-proxy-client/server');
 *   const { server, client } = await startServer({ port: 3456 });
 */
async function startServer(options = {}) {
  const logger = createLogger(options.logLevel);

  const clientConfig = {
    host: options.proxyHost || process.env.JT400_PROXY_HOST || 'localhost',
    port: options.proxyPort || parseInt(process.env.JT400_PROXY_PORT || '9400', 10),
    numLinks: options.proxyLinks || parseInt(process.env.JT400_PROXY_NUM_LINKS || '3', 10),
    requestTimeoutMs: options.requestTimeoutMs || parseInt(process.env.JT400_PROXY_REQUEST_TIMEOUT_MS || '30000', 10),
  };

  const port = options.port || parseInt(process.env.JT400_FACADE_HTTP_PORT || '3456', 10);
  const host = options.host || process.env.JT400_FACADE_HOST || '127.0.0.1';

  const Client = getClientClass();
  const client = options.client || new Client(clientConfig);

  logger.info('Connecting to jt400-proxy...', { proxy: clientConfig });

  await client.connect();

  const { app } = createFacadeApp(client, logger);

  // Enhance /metrics with process-level data (createFacadeApp already has the base + pool-stats)
  const startTime = Date.now();
  const originalMetrics = app._router.stack.find(r => r.route && r.route.path === '/metrics');
  // We append an enhanced version by overriding the route
  app.get('/metrics', async (req, res) => {
    const clientStats = client.getStats();
    const mem = process.memoryUsage();
    let poolStats = {};
    try { poolStats = await client.getPoolStats(); } catch (e) { poolStats = { error: e.message }; }

    res.json({
      uptimeSeconds: Math.floor((Date.now() - startTime) / 1000),
      nodeVersion: process.version,
      pid: process.pid,
      memory: { rss: mem.rss, heapUsed: mem.heapUsed, heapTotal: mem.heapTotal, external: mem.external },
      ...clientStats,
      pool: poolStats,
      timestamp: Date.now(),
    });
  });

  const server = app.listen(port, host, () => {
    logger.info('jt400-proxy-client standalone server started', {
      address: `http://${host}:${port}`,
      proxy: clientConfig,
    });
    logger.info('Available endpoints: POST /query, POST /execute, POST /batch, POST /parallel, GET /health, GET /stats, GET /metrics');
  });

  const close = () => new Promise((resolve) => {
    server.close(() => {
      client.close();
      logger.info('Server shut down gracefully.');
      resolve();
    });
  });

  return { server, client, app, close, logger };
}

module.exports = {
  createFacadeApp,
  startServer,
};
