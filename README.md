# jt400-proxy

Stable jt400 / AS400 access for Node.js (and other) via a dedicated Java proxy with HikariCP connection pooling.

## The Problem

Direct use of `node-jt400` (or similar) from Node.js frequently suffers from connection drops, erratic recovery, and instability under load or transient network conditions to the IBM i (AS/400).

## The Solution

- A small, dedicated **Java service** owns the real jt400 JDBC connections.
- It uses **HikariCP** (battle-tested pooling) instead of ad-hoc or built-in pools.
- It exposes a **single TCP endpoint** using a simple length-prefixed JSON framing protocol over persistent full-duplex sockets.
- A **Node.js client** maintains a small number of these duplex connections (multiplexing many logical queries via request IDs) and presents a clean facade.
- The same Node client powers a **local HTTP server** that the rest of your Node.js apps/microservices call (POST /query, etc.). This gives you the ergonomics you want while the hard part (stable AS400 connectivity + pooling) lives in the JVM.

Result: far fewer "connection dropped" surprises, tunable pool sizing in one place, easy observability, and the ability for one proxy JVM to serve many Node processes.

## Architecture (Duplex TCP + Node HTTP Facade)

```
Node apps / microservices
        |
        | HTTP (simple JSON)
        v
Node: @eeworx-dev/jt400-proxy-client  ( + optional express facade on e.g. :3456 )
   (2-5 persistent duplex TCP sockets, request id multiplexing, auto-reconnect)
        |
        | framed TCP (length32 + JSON {id, op, sql, params})
        v
Java: jt400-proxy-server (one TCP port, e.g. :9400)
   - HikariCP pool (e.g. 20 conns)
   - Accepts framed requests, runs on pooled JDBC conn, replies with results
        |
        v
AS/400 (DB2 for i) via jt400 JDBC
```

The Java side speaks the "duplex" protocol. The Node side speaks HTTP to the world and duplex TCP to Java.

## Node.js Client Modes

The `@eeworx-dev/jt400-proxy-client` package supports two usage modes:

### Mode 1: Library / Dependency (integrated into your app)

```js
const Jt400ProxyClient = require('@eeworx-dev/jt400-proxy-client');

const client = new Jt400ProxyClient({
  host: 'localhost',
  port: 9400,
  numLinks: 3
});

await client.connect();
const rows = await client.query('SELECT * FROM ... WHERE ID = ?', [123]);
```

Use this when you want to embed the client directly inside existing services.

### Mode 2: Standalone HTTP Server (recommended / preferred)

Run the package as a dedicated service that exposes a simple HTTP API:

```bash
npx @eeworx-dev/jt400-proxy-client
# or after global install
@eeworx-dev/jt400-proxy-client
```

**New in this version** — full CLI + config file + PM2 support:

```bash
@eeworx-dev/jt400-proxy-client \
  --port 4000 \
  --proxy-host 10.0.0.42 \
  --proxy-port 9400 \
  --log-level debug

# Or using a config file
@eeworx-dev/jt400-proxy-client --config ./my-proxy.json
```

Supported flags (all also available as environment variables):
- `--port`, `--host`
- `--proxy-host`, `--proxy-port`, `--proxy-links`
- `--config` (path to JSON file)
- `--log-level` (info, debug, warn, error)

### Running with PM2 (recommended for production)

```bash
cd client
npm install -g pm2          # one time
pm2 start ecosystem.config.js --env production
pm2 save
pm2 startup

# Recommended: install log rotation module
pm2 install pm2-logrotate
pm2 set pm2-logrotate:max_size 20M
pm2 set pm2-logrotate:retain   10
pm2 set pm2-logrotate:compress true
```

See `client/ecosystem.config.js` for a ready-to-use configuration (includes log rotation, memory limits, graceful shutdown, etc.).

You can also pass environment variables directly:

```bash
pm2 start bin/cli.js --name @eeworx-dev/jt400-proxy-client -- \
  --proxy-host 10.0.0.5 --port 3456
```

### Metrics

The server exposes several endpoints useful for monitoring and operations:

- `GET /stats` — client connection stats + Hikari pool metrics + server info (uptime, pid)
- `GET /pool-stats` — HikariCP metrics directly from the Java side:
  - `activeConnections`, `idleConnections`, `totalConnections`
  - `threadsAwaitingConnection`
  - `maxPoolSize`, `minIdle`
- `GET /metrics` — rich JSON combining:
  - Process memory (rss, heap)
  - Uptime, pid, node version
  - All client stats
  - Full pool stats from Java

These endpoints work great with PM2, custom dashboards, or simple health checkers. Native Prometheus text format is not included yet but the JSON is easy to scrape/transform.

On the Java side, Hikari metrics are always available via the `pool-stats` operation over the duplex protocol.

**Config file example** (see `client/config.example.json`):

```json
{
  "proxy": { "host": "10.0.0.5", "port": 9400, "numLinks": 5 },
  "facade": { "port": 3456 },
  "logLevel": "info"
}
```

You can also start the server programmatically (great for tests or embedding):

```js
const { startServer } = require('@eeworx-dev/jt400-proxy-client/server');

const { server, client, close } = await startServer({
  port: 3456,
  proxyHost: '10.0.0.5',
  logLevel: 'info'
});

// later...
await close();
```

Other microservices can then call this local (or nearby) HTTP endpoint without needing to know anything about jt400, duplex sockets, or the Java proxy.

You can also require the server factory if you want to embed the HTTP facade inside your own Express app:

```js
const { createFacadeApp } = require('@eeworx-dev/jt400-proxy-client/server');
const { app } = createFacadeApp();
```

**The author prefers Mode 2** for most deployments because it creates a clean boundary and makes the proxy easy to operate, monitor, and scale independently.

## Requirements (v1)

1. TCP endpoint in Java receiving `{ "sql": "...", "params": [...] }` (plus correlation `id` and `op`).
2. Real connection pool (Hikari), execute, return `data: [...]` (array of row objects) for SELECTs or `affectedRows` for DML.
3. Node client facade + sample that exposes its own HTTP endpoint for easy consumption by other Node code.

## Quick Start (High Level)

### 1. Java Proxy

```bash
cd server
# (edit or export env for your AS400)
export AS400_HOST=192.168.1.10
export AS400_USER=...
export AS400_PASSWORD=...
export AS400_DATABASE=MYLIB
export HIKARI_MAX_POOL_SIZE=15
export PROXY_TCP_PORT=9400

mvn clean package
java -jar target/jt400-proxy-server-*.jar
```

It will log pool initialization and "Listening on 0.0.0.0:9400".

### 2. Node Facade (recommended for most apps)

```bash
cd client
npm install
# (configure)
export JT400_PROXY_HOST=localhost
export JT400_PROXY_PORT=9400
export JT400_FACADE_HTTP_PORT=3456

node bin/cli.js
```

Now call it from anywhere:

```bash
curl -X POST http://localhost:3456/query \
  -H 'content-type: application/json' \
  -d '{"sql":"SELECT 1 as ONE FROM SYSIBM.SYSDUMMY1","params":[]}'
```

Expect:

```json
{"data":[{"ONE":1}]}
```

Use the client directly in code too (see `example-direct.js`).

Batch and parallel (for bulk workloads, modeled on patterns from related pool implementations):

```bash
curl -X POST http://localhost:3456/batch \
  -H 'content-type: application/json' \
  -d '{"queries":[{"name":"Q1","sql":"SELECT 1 as ONE FROM SYSIBM.SYSDUMMY1","params":[]}]}'

curl -X POST http://localhost:3456/parallel \
  -H 'content-type: application/json' \
  -d '{"queries":[...], "concurrency": 5}'
```

They also support `txId` for running the whole batch under a parked transaction.
You can also pass `"txId": "new"` to have the facade auto-start + auto-commit a tx around the batch.

### Transactions (parked connections)

Because the protocol is multiplexed over stateless framed messages, a tx is represented by a server-parked JDBC connection identified by `txId`.

**Low level (explicit):**
```js
const txId = await client.beginTransaction();
await client.query('INSERT ...', [..], { txId });
await client.execute('UPDATE ...', [..], { txId });
await client.commit(txId);
```

**Recommended: use a handle (deduplicates txId plumbing):**
```js
const tx = await client.transaction();           // or client.transaction(async (tx) => { ... })
await tx.query('SELECT ...', []);
await tx.execute('UPDATE ...', [val, id]);
await tx.commit();
// on error path: await tx.rollback()
```

The `Transaction` handle also works when using the HTTP facade (POST body can include `txId` on `/query`, `/execute`, `/batch` etc, or use the `/tx/:txId/*` routes).

Legacy `pool.transaction(fn)` style is supported via the jt400-proxy-adapter (and now powered by the same handle).

See `getPoolStats()` output for active parked txs (used by server sweeper for timeout).

## Protocol (Framed TCP)

Wire format (every message):
- 4 bytes big-endian uint32 = length of following payload
- payload = UTF-8 JSON

Request:
```json
{"id":"c-1-7","op":"query","sql":"SELECT * FROM FOO WHERE X=?","params":[42]}
{"id":"c-1-8","op":"execute","sql":"UPDATE BAR SET Y=? WHERE ID=?","params":["hello", 7]}
{"id":"c-1-9","op":"ping"}
```

Success responses:
- query: `{"id":..., "success":true, "data":[ {...row...}, ... ], "rowCount":N, "durationMs":12, "connection":"..."}`
- execute: `{"id":..., "success":true, "affectedRows":3, "durationMs":8, "connection":"..."}`
- ping: `{"id":..., "success":true, "pong":true}`

Error:
`{"id":..., "success":false, "error":"...", "sqlState":"HY000", "durationMs":5}`

The Node client handles correlation by `id` and can have multiple in-flight requests per socket.

## Configuration

### Java (env vars preferred)

Environment variables are the recommended way to configure the Java proxy.

Key variables:
- `AS400_HOST`, `AS400_USER`, `AS400_PASSWORD` (mandatory)
- `AS400_DATABASE` (the part after host/ in jdbc:as400://host/db)
- `AS400_JDBC_PROPS` (optional extra properties string, e.g. ";translate binary=true;...")
- `PROXY_TCP_PORT` (default 9400)
- `HIKARI_MAX_POOL_SIZE` (default 20)
- Other Hikari tuning: `HIKARI_MIN_IDLE`, `HIKARI_CONNECTION_TIMEOUT_MS`, etc.
- `TX_TIMEOUT_MS`, `TX_SWEEPER_INTERVAL_MS` (for parked transaction lifetime)

**Convenience env file support**:
- Copy `server/.env.example` → `server/.env` (or `.env.local`)
- `run.sh` / `start.sh` will automatically source it (using `set -a`).
- On Windows, copy relevant values into `server/env.bat` (see `env.bat.example`). `run.bat` / `start.bat` will call it if present.

You can also specify a custom env file explicitly as the first argument (recommended when you have multiple environments):

```bash
# Unix
server/scripts/run.sh prod.env
server/scripts/start.sh /etc/jt400/my-server.env

# Windows
server\scripts\run.bat prod.bat
server\scripts\start.bat C:\config\prod.bat
```

Supported forms: `script.sh myfile.env`, `script.sh --env myfile.env`, `script.sh --env-file myfile.env`.

The rest of the command line after the env file (if any) is still forwarded to the Java process.

See `server/src/main/resources/application.properties.example` for the full list and descriptions.

### Node Client + Facade

- `JT400_PROXY_HOST` / `JT400_PROXY_PORT`
- `JT400_PROXY_NUM_LINKS` (default 3) — how many persistent duplex sockets
- `JT400_PROXY_REQUEST_TIMEOUT_MS`
- `JT400_FACADE_HTTP_PORT` (for the facade)

See `client/config.example.json`.

## Building & Running

See `server/scripts/run.sh` (or .bat) and the start/stop wrappers in `server/scripts/`.
Both the run and start scripts support loading from `.env` / `env.bat` automatically (or a custom file passed as the first argument).

### Building a Distribution

To create a ready-to-deploy package (recommended for production use):

```bash
cd server
mvn clean package -DskipTests
```

The assembly is bound to the `package` lifecycle phase, so the distribution is automatically produced as **additional build artifacts**:

- `target/jt400-proxy-server-*-dist.tar.gz`
- `target/jt400-proxy-server-*-dist.zip`

(The main artifact remains the shaded executable JAR.)

The distribution contains:
- `bin/` – start/stop scripts and run scripts (copied from `server/scripts/`)
- `lib/` – the executable shaded JAR (jt400-proxy-server-<version>.jar)
- `conf/` – example configuration files
- `logs/` – empty directory for runtime logs

See `README.md` (the README-dist.md is renamed to README.md at the root of the dist archive via the assembly descriptor) for distribution-specific instructions.

See also the helper script `server/setup-pm2-logrotate.js` (for the Node side of bb2-server-node).

Recommended JVM: `-Xms512m -Xmx2g` (or higher if you have very large result sets or many pools).

## Result Shape & Types

- Column names come from the driver (often UPPERCASE on AS/400).
- Values: strings, numbers (int/long/double), nulls. Dates/times usually arrive as strings or java.sql.* objects that Jackson turns into sensible JSON.
- For heavy type control, map in your application layer or extend QueryProcessor.

## Production Tips

- Run the Java proxy on a stable host with good connectivity to the AS/400 (same rack or low-latency segment is ideal).
- One proxy can be shared by many Node processes (they just open a few duplex links each).
- Tune Hikari `maxLifetime` and `connectionTestQuery` to deal with AS/400 job timeouts.
- Watch the Java logs for pool stats and slow queries.
- The Node client auto-reconnects on link failure; in-flight requests at the moment of death will error (callers should retry at a higher level for idempotent queries).

## Troubleshooting

- "No suitable driver" → jt400.jar not on classpath (shade or -cp issue).
- Connections hang or reset → check `tcp no delay`, firewalls, AS400 QSYSOPR messages, CCSID / translate binary settings in the JDBC URL.
- Slow queries → use `EXPLAIN`, proper indexes, or reduce result set size. The proxy adds negligible overhead.
- Many "connection closed" in Node → increase `numConnections` (duplex links) or Hikari pool size, or investigate network between Node host(s) and the proxy.

## Non-Goals / Future

- Distributed transactions / 2PC
- Cursor / streaming large results (fetch first N for now)
- Built-in auth on the proxy protocol (use network controls)
- WebSocket or gRPC frontends (easy to add later on the Java side if needed)

## License / Credits

jt400 (JTOpen) is from IBM / open source. HikariCP by Brett Wooldridge. This proxy is a thin stable bridge.

## Contributing / Support

Internal tool — open issues with logs, query examples, and AS/400 version.

---

**Status**: Core implementation complete (per approved plan).

## What Was Built

- **Java server** (`server/`):
  - Proper HikariCP + jt400 JDBC pooling (the key stability improvement).
  - Single TCP port, persistent full-duplex connections using simple robust length-prefixed JSON framing.
  - `QueryProcessor` that acquires a pooled connection, binds params, executes, and returns `data: [ {col:val, ...}, ... ]` arrays (or `affectedRows`).
  - Clean shutdown, stats via Hikari, graceful degradation if the AS/400 is unreachable at startup.
  - Runnable shaded jar.

- **Node client + facade** (`client/`):
  - `Jt400ProxyClient` — manages N persistent duplex `FramedDuplexLink`s, round-robin, request id correlation, auto-reconnect, `query()` / `execute()` / `ping()` API.
  - Pure (no deps) framing implementation.
  - `facade.js` (exposed as `./facade` or `./server`) — the HTTP facade implementation that exposes `/query`, `/execute`, `/health`, `/stats`, and the new tx endpoints. This is the "sample nodejs client ... exposing its own http endpoint". The executable lives in `bin/cli.js`.
  - `example-direct.js` — use the facade class directly from your own code.
  - Built-in self-test for the framing protocol.

All verification steps from the plan (build, framing self-test, integrated Java+Node duplex smoke with live listener + request/response) have been executed successfully in this session.

## Running the Smoke Test Yourself (no AS/400 needed for basic protocol check)

```bash
# Terminal 1 - Java (will warn about pool but still export the TCP endpoint)
cd server

# Option A: export variables directly
export AS400_HOST=127.0.0.1 AS400_USER=dummy AS400_PASSWORD=dummy AS400_DATABASE=FAKE PROXY_TCP_PORT=19400

# Option B: use env file (recommended for local work)
# cp .env.example .env
# edit .env with your values
# scripts/run.sh will source it automatically
# You can also pass a specific file: ./scripts/run.sh my-test.env

mvn clean package -DskipTests -q
./scripts/run.sh

# Terminal 2 - Node client talks to it over duplex TCP
cd ../client
npm install
JT400_PROXY_HOST=127.0.0.1 JT400_PROXY_PORT=19400 node -e '
  const C = require("./index");
  (async () => {
    const c = new C({numLinks:1});
    await c.connect();
    console.log("ping:", await c.ping());
    try { console.log("rows:", await c.query("SELECT 1 FROM SYSIBM.SYSDUMMY1")); }
    catch(e){ console.log("query err (expected):", e.message); }
    c.close();
  })();
'
```

## Publishing & Consumption (`@eeworx-dev/jt400-proxy-client`)

The reusable piece for other projects (including bb2-server-node + the adapter) is the **Node client package** located in the `client/` directory.

### 1. Publish the client package (recommended for team use)

From the root of this repo:

```bash
cd client

# (Optional but recommended) bump the version first
npm version patch   # or minor / major

# Login if you haven't (or configure a private registry / GitHub Packages)
npm login

# Publish
npm publish
```

**Tips for your environment**:
- Publish as a **public scoped package** (`@eeworx-dev/jt400-proxy-client`) on GitHub Packages. The source repository can remain private.
- Or run a private Verdaccio / Artifactory / GitHub Packages registry and point `.npmrc` at it.
- The `package.json` already has a `"files"` field and `"prepublishOnly": "npm test"` hook to keep the published tarball clean.
- After publishing, anyone (or any project) can do `npm install @eeworx-dev/jt400-proxy-client`.

### 2. Global install (mainly for the CLI / standalone facade)

```bash
npm install -g @eeworx-dev/jt400-proxy-client
@eeworx-dev/jt400-proxy-client   # starts the HTTP facade on port 3456 (configurable via env)
```

This is useful when you want other services (or bb2-server-node itself) to talk pure HTTP to the facade without pulling the client as a code dependency.

### 3. Consume in another project (e.g. bb2-server-node)

In the consuming project's `package.json`:

```json
"dependencies": {
  "@eeworx-dev/jt400-proxy-client": "^0.1.0"
}
```

Then `npm install`.

In your adapter (or directly):

```js
const Jt400ProxyClient = require('@eeworx-dev/jt400-proxy-client');
// or for the facade/server helpers:
const { createFacadeApp, startServer } = require('@eeworx-dev/jt400-proxy-client/server');
```

The `jt400-proxy-adapter.js` (the bridge for `ms_database.js`) does exactly `require('@eeworx-dev/jt400-proxy-client')` internally, so once the package is published (or linked), the adapter will just work.

### Quick local dev alternative (no publish needed)

While developing:

```bash
# In this repo
cd client
npm link

# In bb2-server-node (or any consumer)
npm link @eeworx-dev/jt400-proxy-client
```

This symlinks it so `require('@eeworx-dev/jt400-proxy-client')` resolves to your local copy.

Global link also works for the CLI:
```bash
npm link
@eeworx-dev/jt400-proxy-client
```

### Java side note

The Node client is only half the story. You still need to build and run the Java `jt400-proxy-server` (the shaded jar) and point the client at its TCP port (default 9400). The Java side is distributed separately (jar + run script / Docker / systemd unit, etc.).

Let me know if you want:
- A scoped package name change (`@eeworx/...`)
- A root-level `npm run publish:client` script
- Better monorepo publishing setup (changesets, etc.)
- Instructions for GitHub Packages instead of npm
- A small publish checklist or CI step

This should unblock using the adapter as a real dependency in bb2-server-node.

For a real AS/400, set the proper `AS400_*` variables (and optionally `HIKARI_*`) — the same commands will then execute real queries through the stable pooled path.

See the individual `server/` and `client/` README sections (or the source headers) for more.

---
