# jt400-proxy-client Distribution

This is a ready-to-run distribution of the jt400-proxy-client (version from package.json).

## Contents

- `index.js`, `facade.js`, `lib/`, `bin/` - The client library and CLI (for standalone HTTP facade)
- `package.json` - For `npm install --production`
- `conf/` - Example configuration (`config.example.json`) and PM2 ecosystem file
- `examples/` - Example usage
- `logs/` - Directory for runtime logs (empty)
- `README.md` - This file

## Quick Start (Standalone Facade / HTTP Server)

### 1. Install dependencies

```bash
npm install --production
```

### 2. Configure

Copy the example and edit it (or use environment variables / CLI flags):

```bash
cp conf/config.example.json my-config.json
```

Key settings (can also be passed via env or CLI):
- `JT400_PROXY_HOST`, `JT400_PROXY_PORT` — where the Java jt400-proxy-server is listening
- `JT400_FACADE_HTTP_PORT` — port this standalone client will listen on (default 3456)

### 3. Run

Using the CLI:

```bash
node bin/cli.js --config my-config.json
# or
npm start
```

Using PM2 (recommended for production):

```bash
pm2 start conf/ecosystem.config.js --env production
```

The standalone client exposes:
- `POST /query`
- `POST /execute`
- `POST /batch`, `POST /parallel`
- `GET /health`
- `GET /stats` (great for monitoring/metrics)

## Using as a Library (Embedded)

In your Node application:

```js
const Jt400ProxyClient = require('@eeworx-dev/jt400-proxy-client');

const client = new Jt400ProxyClient({
  host: 'localhost',
  port: 9400,
  numLinks: 3
});

await client.connect();
const rows = await client.query('SELECT ...', params);
```

See `examples/example-direct.js` and the main README for more.

## Notes

- This distribution does **not** include node_modules. Run `npm install --production` after extracting.
- The client maintains persistent duplex TCP connections to the Java server.
- For the Java server side, see the sibling server distribution.
