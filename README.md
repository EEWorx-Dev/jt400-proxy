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
Node: jt400-proxy-client  ( + optional express facade on e.g. :3456 )
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

The `jt400-proxy-client` package supports two usage modes:

### Mode 1: Library / Dependency (integrated into your app)

```js
const Jt400ProxyClient = require('jt400-proxy-client');

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
npx jt400-proxy-client
# or after global install
jt400-proxy-client
```

**New in this version** — full CLI + config file + PM2 support:

```bash
jt400-proxy-client \
  --port 4000 \
  --proxy-host 10.0.0.42 \
  --proxy-port 9400 \
  --log-level debug

# Or using a config file
jt400-proxy-client --config ./my-proxy.json
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
pm2 start bin/server.js --name jt400-proxy-client -- \
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
const { startServer } = require('jt400-proxy-client/server');

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
const { createFacadeApp } = require('jt400-proxy-client/server');
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

node server.js
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

- `AS400_HOST`, `AS400_USER`, `AS400_PASSWORD` (mandatory)
- `AS400_DATABASE` (the part after host/ in jdbc:as400://host/db)
- `AS400_JDBC_PROPS` (optional extra properties string, e.g. ";translate binary=true;...")
- `PROXY_TCP_PORT` (default 9400)
- `HIKARI_MAX_POOL_SIZE` (default 20)
- Other Hikari tuning: `HIKARI_MIN_IDLE`, `HIKARI_CONNECTION_TIMEOUT_MS`, etc.

See `server/src/main/resources/application.properties.example` (when created).

### Node Client + Facade

- `JT400_PROXY_HOST` / `JT400_PROXY_PORT`
- `JT400_PROXY_NUM_LINKS` (default 3) — how many persistent duplex sockets
- `JT400_PROXY_REQUEST_TIMEOUT_MS`
- `JT400_FACADE_HTTP_PORT` (for server.js)

See `client/config.example.json`.

## Building & Running

See `server/run.sh` (or .bat) and the client package.json scripts (when added).

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
  - `server.js` — the sample HTTP facade (uses express) that exposes `/query`, `/execute`, `/health`, `/stats`. This is the "sample nodejs client ... exposing its own http endpoint".
  - `example-direct.js` — use the facade class directly from your own code.
  - Built-in self-test for the framing protocol.

All verification steps from the plan (build, framing self-test, integrated Java+Node duplex smoke with live listener + request/response) have been executed successfully in this session.

## Running the Smoke Test Yourself (no AS/400 needed for basic protocol check)

```bash
# Terminal 1 - Java (will warn about pool but still export the TCP endpoint)
cd server
export AS400_HOST=127.0.0.1 AS400_USER=dummy AS400_PASSWORD=dummy AS400_DATABASE=FAKE PROXY_TCP_PORT=19400
mvn clean package -DskipTests -q
./run.sh

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

For a real AS/400, set the proper `AS400_*` variables (and optionally `HIKARI_*`) — the same commands will then execute real queries through the stable pooled path.

See the individual `server/` and `client/` README sections (or the source headers) for more.

---

See the detailed plan at the session plan.md for the original design rationale, class sketches, incremental order, and full verification strategy.
