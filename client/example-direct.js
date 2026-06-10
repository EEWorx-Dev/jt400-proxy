'use strict';

/**
 * Example of using the Jt400ProxyClient facade directly from Node code
 * (no HTTP in between). Useful inside the same process or for tests.
 */

const Jt400ProxyClient = require('./index');

async function main() {
  const client = new Jt400ProxyClient({
    host: process.env.JT400_PROXY_HOST || 'localhost',
    port: parseInt(process.env.JT400_PROXY_PORT || '9400', 10),
    numLinks: 2
  });

  try {
    await client.connect();

    console.log('ping:', await client.ping());

    // Simple health query that works on any AS/400
    const rows = await client.query('SELECT 1 as ONE FROM SYSIBM.SYSDUMMY1');
    console.log('query result:', rows);

    // Example DML (will actually modify data — commented for safety)
    // const res = await client.execute('INSERT INTO ... VALUES(?,?)', [1, 'x']);
    // console.log('affected:', res.affectedRows);

    console.log('stats:', JSON.stringify(client.getStats(), null, 2));
  } catch (e) {
    console.error('Direct example error:', e.message);
    if (e.sqlState) console.error('sqlState:', e.sqlState);
    process.exitCode = 1;
  } finally {
    client.close();
  }
}

main();
