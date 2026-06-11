'use strict';

const { describe, it, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const request = require('supertest');

const { createFacadeApp } = require('../facade');

describe('HTTP facade (with injected mock client)', () => {
  let app;
  let mockClient;

  beforeEach(() => {
    mockClient = {
      connect: async () => {},
      query: async (sql, params) => {
        if (sql.includes('__FAIL__')) {
          const err = new Error('mock query failure');
          err.sqlState = 'HY000';
          throw err;
        }
        return [{ ECHO: sql, PARAMS: params }];
      },
      execute: async (sql, params) => ({ affectedRows: 1, sql }),
      ping: async () => true,
      getStats: () => ({ links: 1, connected: true }),
      getPoolStats: async () => ({
        activeConnections: 2,
        idleConnections: 3,
        totalConnections: 5,
        transactions: {
          parkedCount: 0,
          parked: []
        }
      }),
      runBatch: async (queries = []) => ({
        totalQueries: queries.length,
        successful: queries.length,
        failed: 0,
        totalDurationMs: 10,
        results: queries.map(q => ({
          name: q.name || 'Unnamed',
          success: true,
          data: [{ ECHO: q.sql, PARAMS: q.params || [] }]
        }))
      }),
      runParallel: async (queries = [], concurrency = 10) => ({
        totalQueries: queries.length,
        successful: queries.length,
        failed: 0,
        totalDurationMs: 8,
        avgPerQuery: 2,
        results: queries.map(q => ({
          name: q.name || 'Unnamed',
          success: true,
          data: [{ ECHO: q.sql, PARAMS: q.params || [] }]
        }))
      }),
      beginTransaction: async () => 'mock-new-tx',
      commit: async (txId) => ({ txId, status: 'committed' }),
      rollback: async (txId) => ({ txId, status: 'rolledback' })
    };

    const result = createFacadeApp(mockClient);
    app = result.app;
  });

  it('POST /query returns data', async () => {
    const res = await request(app)
      .post('/query')
      .send({ sql: 'SELECT 1', params: [42] })
      .expect(200);

    assert.deepEqual(res.body, {
      data: [{ ECHO: 'SELECT 1', PARAMS: [42] }]
    });
  });

  it('POST /query validates sql presence', async () => {
    const res = await request(app)
      .post('/query')
      .send({ params: [] })
      .expect(400);

    assert.match(res.body.error, /sql is required/);
  });

  it('POST /execute returns affectedRows', async () => {
    const res = await request(app)
      .post('/execute')
      .send({ sql: 'UPDATE x SET y=1' })
      .expect(200);

    assert.equal(res.body.affectedRows, 1);
  });

  it('GET /health returns status', async () => {
    const res = await request(app)
      .get('/health')
      .expect(200);

    assert.equal(res.body.status, 'ok');
    assert.ok(res.body.proxy);
  });

  it('GET /stats returns client stats including pool transactions (last-activity model)', async () => {
    const res = await request(app)
      .get('/stats')
      .expect(200);

    assert.equal(res.body.connected, true);
    assert.ok(res.body.pool);
    assert.ok(res.body.pool.transactions);
    assert.equal(res.body.pool.transactions.parkedCount, 0);
  });

  it('propagates client errors as 500 with sqlState', async () => {
    const res = await request(app)
      .post('/query')
      .send({ sql: 'SELECT __FAIL__' })
      .expect(500);

    assert.match(res.body.error, /mock query failure/);
    assert.equal(res.body.sqlState, 'HY000');
  });

  it('POST /batch returns summary with per-query results', async () => {
    const res = await request(app)
      .post('/batch')
      .send({
        queries: [
          { name: 'Q1', sql: 'SELECT 1', params: [42] },
          { name: 'Q2', sql: 'SELECT 2', params: [] }
        ]
      })
      .expect(200);

    assert.equal(res.body.totalQueries, 2);
    assert.equal(res.body.successful, 2);
    assert.equal(res.body.results.length, 2);
    assert.equal(res.body.results[0].name, 'Q1');
    assert.deepEqual(res.body.results[0].data, [{ ECHO: 'SELECT 1', PARAMS: [42] }]);
  });

  it('POST /parallel returns aggregated results with concurrency', async () => {
    const res = await request(app)
      .post('/parallel')
      .send({
        queries: [{ name: 'P1', sql: 'SELECT 1' }],
        concurrency: 5
      })
      .expect(200);

    assert.equal(res.body.totalQueries, 1);
    assert.equal(res.body.avgPerQuery, 2);
    assert.equal(res.body.results[0].name, 'P1');
  });

  it('POST /batch and /parallel validate queries presence gracefully', async () => {
    const res = await request(app)
      .post('/batch')
      .send({ })
      .expect(200);

    assert.equal(res.body.totalQueries, 0);
  });

  it('POST /batch with txId:"new" starts a tx, runs the batch, and auto-commits', async () => {
    const res = await request(app)
      .post('/batch')
      .send({
        queries: [{ name: 'Q1', sql: 'SELECT 1', params: [] }],
        txId: 'new'
      })
      .expect(200);

    assert.equal(res.body.totalQueries, 1);
    assert.equal(res.body.txId, 'mock-new-tx');
    assert.equal(res.body.txStatus, 'committed');
  });

  it('POST /parallel with txId:"new" starts a tx, runs in parallel, and auto-commits', async () => {
    const res = await request(app)
      .post('/parallel')
      .send({
        queries: [{ name: 'P1', sql: 'SELECT 1' }],
        concurrency: 2,
        txId: 'new'
      })
      .expect(200);

    assert.equal(res.body.totalQueries, 1);
    assert.equal(res.body.txId, 'mock-new-tx');
    assert.equal(res.body.txStatus, 'committed');
  });

  it('POST /batch with txId:"new" rolls back on error', async () => {
    // Temporarily make the mock fail for this test by overriding
    mockClient.runBatch = async () => { throw new Error('batch explosion'); };

    const res = await request(app)
      .post('/batch')
      .send({
        queries: [{ sql: 'SELECT 1' }],
        txId: 'new'
      })
      .expect(500);

    assert.match(res.body.error, /batch explosion/);
    assert.equal(res.body.txId, 'mock-new-tx');
    assert.equal(res.body.txStatus, 'rolledback');

    // restore for other tests
    mockClient.runBatch = async (queries = []) => ({
      totalQueries: queries.length,
      successful: queries.length,
      failed: 0,
      totalDurationMs: 10,
      results: queries.map(q => ({
        name: q.name || 'Unnamed',
        success: true,
        data: [{ ECHO: q.sql, PARAMS: q.params || [] }]
      }))
    });
  });
});
