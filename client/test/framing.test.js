'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const net = require('net');

const Jt400ProxyClient = require('../index');

describe('framing protocol (self-test)', () => {
  it('roundtrips a request/response over length-prefixed JSON', async () => {
    const server = net.createServer((sock) => {
      let buf = Buffer.alloc(0);
      sock.on('data', (chunk) => {
        buf = Buffer.concat([buf, chunk]);
        while (buf.length >= 4) {
          const len = buf.readUInt32BE(0);
          if (buf.length < 4 + len) break;
          const payload = buf.slice(4, 4 + len);
          buf = buf.slice(4 + len);

          try {
            const obj = JSON.parse(payload.toString('utf8'));
            const reply = {
              id: obj.id,
              success: true,
              pong: true,
              echo: obj,
            };
            const out = Buffer.from(JSON.stringify(reply), 'utf8');
            const header = Buffer.allocUnsafe(4);
            header.writeUInt32BE(out.length, 0);
            sock.write(Buffer.concat([header, out]));
          } catch (e) {
            // ignore bad frames in test
          }
        }
      });
    });

    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const port = server.address().port;

    const ok = await Jt400ProxyClient.selfTestFraming();
    assert.equal(ok, true);

    server.close();
  });
});
