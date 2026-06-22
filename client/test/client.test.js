'use strict';

const { describe, it, before, after } = require('node:test');
const assert = require('node:assert/strict');
const net = require('net');

const Jt400ProxyClient = require('../index');

describe('Jt400ProxyClient (with mock server)', () => {
  let server;
  let port;

  before(async () => {
    // Simple mock server that understands the jt400-proxy protocol
    server = net.createServer((sock) => {
      let buf = Buffer.alloc(0);
      sock.on('data', (chunk) => {
        buf = Buffer.concat([buf, chunk]);
        while (buf.length >= 4) {
          const len = buf.readUInt32BE(0);
          if (buf.length < 4 + len) break;
          const payload = buf.slice(4, 4 + len);
          buf = buf.slice(4 + len);

          let req;
          try { req = JSON.parse(payload.toString('utf8')); } catch { continue; }

          const id = req.id;

          if (req.op === 'ping') {
            write(sock, { id, success: true, pong: true });
          } else if (req.op === 'begin-tx') {
            const txId = 'tx-mock-' + Date.now();
            write(sock, { id, success: true, txId, status: 'active' });
          } else if (req.op === 'commit-tx' || req.op === 'rollback-tx') {
            write(sock, { id, success: true, txId: req.txId, status: req.op === 'commit-tx' ? 'committed' : 'rolled_back' });
          } else if (req.op === 'query') {
            if (req.sql && req.sql.includes('__ERROR__')) {
              const resp = { id, success: false, error: 'simulated server error', sqlState: '42S02' };
              if (req.txId) resp.txId = req.txId;
              write(sock, resp);
              return;
            }
            // Fake a couple of rows
            const resp = {
              id,
              success: true,
              data: [{ COL1: 'value1', COL2: 42 }],
              rowCount: 1,
              durationMs: 3,
            };
            if (req.txId) resp.txId = req.txId;
            write(sock, resp);
          } else if (req.op === 'execute') {
            const resp = {
              id,
              success: true,
              affectedRows: 1,
              durationMs: 5,
            };
            if (req.txId) resp.txId = req.txId;
            write(sock, resp);
          } else if (req.op === 'pool-stats' || req.op === 'stats') {
            write(sock, {
              id,
              success: true,
              activeConnections: 5,
              idleConnections: 3,
              transactions: {
                parkedCount: 1,
                parked: [{
                  txId: 'tx-mock-123',
                  startTime: Date.now() - 5000,
                  lastUsed: Date.now() - 50,
                  ageMs: 5000,
                  idleMs: 50
                }]
              }
            });
          } else {
            write(sock, { id, success: false, error: 'unknown op' });
          }
        }
      });
    });

    await new Promise((r) => server.listen(0, '127.0.0.1', r));
    port = server.address().port;
  });

  after(() => {
    if (server) server.close();
  });

  it('connects, pings, queries and executes', async () => {
    const client = new Jt400ProxyClient({
      host: '127.0.0.1',
      port,
      numLinks: 1,
      requestTimeoutMs: 2000,
    });

    await client.connect();

    const pingOk = await client.ping();
    assert.equal(pingOk, true);

    const rows = await client.query('SELECT 1', []);
    assert.deepEqual(rows, [{ COL1: 'value1', COL2: 42 }]);

    const exec = await client.execute('UPDATE FOO SET X=1', []);
    assert.equal(exec.affectedRows, 1);

    client.close();
  });

  it('surfaces errors from the server', async () => {
    const client = new Jt400ProxyClient({
      host: '127.0.0.1',
      port,
      numLinks: 1,
      requestTimeoutMs: 2000,
    });
    await client.connect();

    await assert.rejects(
      () => client.query('SELECT 1 FROM __ERROR__', []),
      /simulated server error/i
    );

    client.close();
  });

  it('runBatch executes queries sequentially and returns a summary', async () => {
    const client = new Jt400ProxyClient({
      host: '127.0.0.1',
      port,
      numLinks: 1,
      requestTimeoutMs: 2000,
    });
    await client.connect();

    const queries = [
      { name: 'Q1', sql: 'SELECT 1', params: [] },
      { name: 'Q2', sql: 'SELECT 2', params: [] },
      { name: 'Q3 error', sql: 'SELECT 1 FROM __ERROR__', params: [] },
    ];

    const result = await client.runBatch(queries);

    assert.equal(result.totalQueries, 3);
    assert.equal(result.successful, 2);
    assert.equal(result.failed, 1);
    assert.ok(typeof result.totalDurationMs === 'number');

    assert.equal(result.results.length, 3);
    assert.equal(result.results[0].name, 'Q1');
    assert.equal(result.results[0].success, true);
    assert.deepEqual(result.results[0].data, [{ COL1: 'value1', COL2: 42 }]);

    assert.equal(result.results[2].success, false);
    assert.match(result.results[2].error, /simulated server error/i);

    client.close();
  });

  it('runParallel executes queries with concurrency and aggregates results', async () => {
    const client = new Jt400ProxyClient({
      host: '127.0.0.1',
      port,
      numLinks: 2,
      requestTimeoutMs: 2000,
    });
    await client.connect();

    const queries = [
      { name: 'P1', sql: 'SELECT 1', params: [] },
      { name: 'P2', sql: 'SELECT 1 FROM __ERROR__', params: [] },
      { name: 'P3', sql: 'SELECT 1', params: [] },
    ];

    const result = await client.runParallel(queries, 2);

    assert.equal(result.totalQueries, 3);
    assert.equal(result.successful, 2);
    assert.equal(result.failed, 1);
    assert.ok(typeof result.avgPerQuery === 'number');

    const successes = result.results.filter(r => r.success);
    assert.equal(successes.length, 2);
    assert.deepEqual(successes[0].data, [{ COL1: 'value1', COL2: 42 }]);

    const failure = result.results.find(r => !r.success);
    assert.match(failure.error, /simulated server error/i);

    client.close();
  });

  it('runBatch and runParallel support txId for existing transactions', async () => {
    const client = new Jt400ProxyClient({
      host: '127.0.0.1',
      port,
      numLinks: 1,
      requestTimeoutMs: 2000,
    });
    await client.connect();

    const txId = 'tx-existing-456';
    const queries = [{ name: 'Qtx', sql: 'SELECT 1', params: [] }];

    const batchRes = await client.runBatch(queries, { txId });
    assert.equal(batchRes.totalQueries, 1);
    assert.equal(batchRes.successful, 1);

    const parallelRes = await client.runParallel(queries, 1, { txId });
    assert.equal(parallelRes.totalQueries, 1);

    client.close();
  });

  it('client.transaction() returns a handle that auto-injects txId (no manual options needed)', async () => {
    const client = new Jt400ProxyClient({
      host: '127.0.0.1',
      port,
      numLinks: 1,
      requestTimeoutMs: 2000,
    });
    await client.connect();

    const handle = await client.transaction();
    assert.ok(handle);
    assert.ok(handle.txId);
    assert.ok(handle.query);
    assert.ok(handle.execute);
    assert.ok(handle.commit);
    assert.ok(handle.rollback);

    // Using the handle should include txId internally
    const rows = await handle.query('SELECT 1');
    assert.equal(rows.length, 1);

    const execRes = await handle.execute('UPDATE foo SET x=1');
    assert.equal(execRes.affectedRows, 1);

    const cRes = await handle.commit();
    assert.equal(cRes.status, 'committed');

    client.close();
  });

  it('client.transaction(fn) invokes callback with handle and supports rollback on sync throw', async () => {
    const client = new Jt400ProxyClient({ host: '127.0.0.1', port, numLinks: 1 });
    await client.connect();

    let sawHandle = false;
    let threw = false;
    try {
      await client.transaction((tx) => {
        sawHandle = !!tx && !!tx.txId;
        throw new Error('boom in tx fn');
      });
    } catch (e) {
      threw = /boom/.test(e.message);
    }
    assert.equal(sawHandle, true);
    assert.equal(threw, true);

    client.close();
  });

  it('getPoolStats returns transactions info with startTime and lastUsed (last-activity model)', async () => {
    const client = new Jt400ProxyClient({
      host: '127.0.0.1',
      port,
      numLinks: 1,
    });
    await client.connect();

    const stats = await client.getPoolStats();
    assert.ok(stats.transactions);
    assert.equal(stats.transactions.parkedCount, 1);
    const tx = stats.transactions.parked[0];
    assert.ok(tx.txId);
    assert.ok(typeof tx.startTime === 'number');
    assert.ok(typeof tx.lastUsed === 'number');
    assert.ok(typeof tx.ageMs === 'number');
    assert.ok(typeof tx.idleMs === 'number');

    client.close();
  });
});

function write(sock, obj) {
  const json = Buffer.from(JSON.stringify(obj), 'utf8');
  const header = Buffer.allocUnsafe(4);
  header.writeUInt32BE(json.length, 0);
  sock.write(Buffer.concat([header, json]));
}
