'use strict';

const { describe, it, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const request = require('supertest');

const { createFacadeApp } = require('../server');

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
      getPoolStats: async () => ({ activeConnections: 2, idleConnections: 3, totalConnections: 5 })
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

  it('GET /stats returns client stats', async () => {
    const res = await request(app)
      .get('/stats')
      .expect(200);

    assert.equal(res.body.connected, true);
  });

  it('propagates client errors as 500 with sqlState', async () => {
    const res = await request(app)
      .post('/query')
      .send({ sql: 'SELECT __FAIL__' })
      .expect(500);

    assert.match(res.body.error, /mock query failure/);
    assert.equal(res.body.sqlState, 'HY000');
  });
});
