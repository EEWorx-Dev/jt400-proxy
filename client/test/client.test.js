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
          } else if (req.op === 'query') {
            if (req.sql && req.sql.includes('__ERROR__')) {
              write(sock, { id, success: false, error: 'simulated server error', sqlState: '42S02' });
              return;
            }
            // Fake a couple of rows
            write(sock, {
              id,
              success: true,
              data: [{ COL1: 'value1', COL2: 42 }],
              rowCount: 1,
              durationMs: 3,
            });
          } else if (req.op === 'execute') {
            write(sock, {
              id,
              success: true,
              affectedRows: 1,
              durationMs: 5,
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
});

function write(sock, obj) {
  const json = Buffer.from(JSON.stringify(obj), 'utf8');
  const header = Buffer.allocUnsafe(4);
  header.writeUInt32BE(json.length, 0);
  sock.write(Buffer.concat([header, json]));
}
