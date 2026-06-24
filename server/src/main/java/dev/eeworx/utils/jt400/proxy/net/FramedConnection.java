package dev.eeworx.utils.jt400.proxy.net;

import dev.eeworx.utils.jt400.proxy.db.HikariPoolManager;
import dev.eeworx.utils.jt400.proxy.db.QueryProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Handles one persistent client duplex TCP connection.
 * Protocol: big-endian int32 length prefix + UTF-8 JSON payload.
 *
 * Responsibilities:
 * - Read frames, parse JSON requests
 * - Dispatch "ping", "query", "execute" (query/execute will later call into QueryProcessor)
 * - Write response frames (same format)
 * - Never tie a DB connection to the lifetime of this socket (use pool per request)
 */
public class FramedConnection implements Runnable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong CONN_ID_SEQ = new AtomicLong(0);
    private static final Logger log = LoggerFactory.getLogger(FramedConnection.class);

    private static class TxContext {
        final Connection conn;
        final long startTime;
        long lastUsed;

        TxContext(Connection c) {
            this.conn = c;
            long now = System.currentTimeMillis();
            this.startTime = now;
            this.lastUsed = now;
        }

        void touch() {
            this.lastUsed = System.currentTimeMillis();
        }
    }

    private final Socket socket;
    private final HikariPoolManager poolManager;
    private final QueryProcessor queryProcessor;
    private final ExecutorService queryExecutor;
    private final long connId;
    private final long txTimeoutMs;
    private final long txSweeperIntervalMs;
    private final Supplier<Connection> txConnectionProvider; // provides a fresh Connection to park for a new tx (Hikari in prod, test DS in contract tests)
    private volatile boolean running = true;

    private final Map<String, TxContext> parkedTx = new ConcurrentHashMap<>();

    private DataInputStream in;
    private DataOutputStream out;

    public FramedConnection(Socket socket, HikariPoolManager poolManager, QueryProcessor queryProcessor, ExecutorService queryExecutor, long txTimeoutMs, long txSweeperIntervalMs, Supplier<Connection> txConnectionProvider) {
        this.socket = socket;
        this.poolManager = poolManager;
        this.queryProcessor = queryProcessor;
        this.queryExecutor = queryExecutor;
        this.connId = CONN_ID_SEQ.getAndIncrement();
        this.txTimeoutMs = txTimeoutMs > 0 ? txTimeoutMs : 300_000;
        this.txSweeperIntervalMs = txSweeperIntervalMs > 0 ? txSweeperIntervalMs : 30_000;
        this.txConnectionProvider = txConnectionProvider;
        startTxSweeper();
    }

    @Override
    public void run() {
        String remote = socket.getRemoteSocketAddress().toString();
        log.info("[Conn-{}] Client connected from {}", connId, remote);

        try {
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            while (running && !socket.isClosed()) {
                // Read length-prefixed frame
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException eof) {
                    break;
                }
                if (length <= 0 || length > 10 * 1024 * 1024) { // 10MB safety
                    writeError(null, "Invalid frame length: " + length);
                    break;
                }

                byte[] payload = new byte[length];
                in.readFully(payload);
                String json = new String(payload, StandardCharsets.UTF_8);

                JsonNode req;
                try {
                    req = MAPPER.readTree(json);
                } catch (Exception e) {
                    log.warn("[Conn-{}] Invalid JSON: {}", connId, e.getMessage());
                    writeError(null, "Invalid JSON: " + e.getMessage());
                    continue;
                }

                String id = req.has("id") ? req.get("id").asText() : "noid";
                String op = req.has("op") ? req.get("op").asText() : "unknown";

                // Dispatch (offload blocking JDBC work)
                final JsonNode requestForWorker = req;
                final String reqId = id;

                switch (op) {
                    case "ping":
                        // Fast path, no DB needed
                        writeResponse(reqId, successPing());
                        break;
                    case "pool-stats":
                    case "stats":
                        // Fast path - return Hikari metrics
                        log.debug("[Conn-{}] pool-stats requested", connId);
                        writeResponse(reqId, getPoolStatsResponse());
                        break;
                    case "begin-tx":
                        queryExecutor.submit(() -> handleBeginTx(reqId, requestForWorker));
                        break;
                    case "commit-tx":
                        queryExecutor.submit(() -> handleCommitOrRollback(reqId, requestForWorker, true));
                        break;
                    case "rollback-tx":
                        queryExecutor.submit(() -> handleCommitOrRollback(reqId, requestForWorker, false));
                        break;
                    case "query":
                    case "execute":
                        queryExecutor.submit(() -> handleQueryOrExecute(reqId, op, requestForWorker));
                        break;
                    default:
                        writeError(reqId, "Unknown op: " + op);
                }
            }
        } catch (IOException e) {
            if (running) {
                log.warn("[Conn-{}] IO error: {}", connId, e.getMessage());
            }
        } finally {
            closeQuietly();
            log.info("[Conn-{}] Client disconnected", connId);
        }
    }

    private void handleQueryOrExecute(String id, String op, JsonNode req) {
        try {
            String sql = req.has("sql") ? req.get("sql").asText() : "";
            List<Object> params = extractParams(req);
            String txId = req.has("txId") ? req.get("txId").asText() : null;

            if (sql.isBlank()) {
                writeError(id, "Missing or empty 'sql'");
                return;
            }

            // Determine effective trimStrings: per-request override or server default (Option B: only affects CHAR/NCHAR)
            boolean trimStrings = queryProcessor.isTrimStrings();
            if (req.has("trimStrings")) {
                trimStrings = req.get("trimStrings").asBoolean();
            }

            QueryProcessor.Result result;
            if (txId != null) {
                TxContext ctx = parkedTx.get(txId);
                Connection txConn = ctx != null ? ctx.conn : null;
                if (txConn == null) {
                    writeError(id, "Invalid or expired txId: " + txId);
                    return;
                }
                ctx.touch();
                try {
                    result = queryProcessor.execute(sql, params, txConn, trimStrings);
                } catch (Exception e) {
                    // Auto-rollback on any error during tx-scoped operation
                    // This prevents leaving the tx in a bad state (e.g. deadlock, constraint violation, etc.)
                    autoRollback(txId, txConn, "error during " + op + ": " + e.getMessage());
                    throw e;
                }
            } else {
                result = queryProcessor.execute(sql, params, trimStrings);
            }

            ObjectNode resp = result.toJson(MAPPER);
            resp.put("id", id);
            resp.put("op", op);
            if (txId != null) resp.put("txId", txId);
            writeResponseRaw(resp);
        } catch (Exception e) {
            writeError(id, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private void handleBeginTx(String id, JsonNode req) {
        long start = System.nanoTime();
        try {
            if (txConnectionProvider == null) {
                throw new IllegalStateException("No txConnectionProvider available for begin-tx");
            }
            Connection c = txConnectionProvider.get();
            c.setAutoCommit(false);

            String txId = generateTxId();
            parkedTx.put(txId, new TxContext(c));

            ObjectNode resp = MAPPER.createObjectNode();
            resp.put("txId", txId);
            resp.put("status", "active");

            long dur = (System.nanoTime() - start) / 1_000_000;
            resp.put("durationMs", dur);

            writeResponse(id, resp);
        } catch (Exception e) {
            writeError(id, "Failed to begin tx: " + e.getMessage());
        }
    }

    private void handleCommitOrRollback(String id, JsonNode req, boolean commit) {
        String txId = req.has("txId") ? req.get("txId").asText() : null;
        if (txId == null) {
            writeError(id, "txId required for " + (commit ? "commit" : "rollback"));
            return;
        }

        TxContext ctx = parkedTx.remove(txId);
        Connection c = ctx != null ? ctx.conn : null;
        if (c == null) {
            writeError(id, "Invalid or expired txId: " + txId);
            return;
        }

        long start = System.nanoTime();
        try {
            if (commit) {
                c.commit();
            } else {
                c.rollback();
            }
            c.setAutoCommit(true);
            c.close();

            ObjectNode resp = MAPPER.createObjectNode();
            resp.put("txId", txId);
            resp.put("status", commit ? "committed" : "rolled_back");

            long dur = (System.nanoTime() - start) / 1_000_000;
            resp.put("durationMs", dur);

            writeResponse(id, resp);
        } catch (Exception e) {
            // Attempt to close anyway
            try {
                c.setAutoCommit(true);
                c.close();
            } catch (Exception ignore) {}
            writeError(id, (commit ? "commit" : "rollback") + " failed: " + e.getMessage());
        }
    }

    private void autoRollback(String txId, Connection c, String reason) {
        parkedTx.remove(txId);
        try {
            c.rollback();
            c.setAutoCommit(true);
            c.close();
            log.warn("[Conn-{}] Auto-rolled back tx {} due to {}", connId, txId, reason);
        } catch (Exception ex) {
            log.error("[Conn-{}] Auto-rollback failed for tx {}: {}", connId, txId, ex.getMessage());
        }
    }

    private String generateTxId() {
        // Short random string, e.g. tx_ + 12 base36 chars
        byte[] bytes = new byte[9]; // ~12 chars base36
        new java.security.SecureRandom().nextBytes(bytes);
        String rand = new java.math.BigInteger(1, bytes).toString(36);
        return "tx_" + rand.substring(0, Math.min(12, rand.length()));
    }

    private void startTxSweeper() {
        Thread sweeper = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(txSweeperIntervalMs);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                for (Iterator<Map.Entry<String, TxContext>> it = parkedTx.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, TxContext> e = it.next();
                    if (now - e.getValue().lastUsed > txTimeoutMs) {
                        TxContext ctx = e.getValue();
                        it.remove();
                        try {
                            ctx.conn.rollback();
                            ctx.conn.setAutoCommit(true);
                            ctx.conn.close();
                            log.warn("[Conn-{}] Sweeper auto-rolled back expired tx {} (last used {}ms ago, started {}ms ago)", 
                                    connId, e.getKey(), now - ctx.lastUsed, now - ctx.startTime);
                        } catch (Exception ex) {
                            log.error("[Conn-{}] Sweeper error for tx {}: {}", connId, e.getKey(), ex.getMessage());
                        }
                    }
                }
            }
        }, "tx-sweeper-" + connId);
        sweeper.setDaemon(true);
        sweeper.start();
    }

    private List<Object> extractParams(JsonNode req) {
        if (!req.has("params") || !req.get("params").isArray()) {
            return List.of();
        }
        List<Object> out = new java.util.ArrayList<>();
        for (JsonNode p : req.get("params")) {
            if (p.isNull()) {
                out.add(null);
            } else if (p.isNumber()) {
                if (p.isInt() || p.isLong()) {
                    out.add(p.longValue());
                } else {
                    out.add(p.doubleValue());
                }
            } else if (p.isBoolean()) {
                out.add(p.booleanValue());
            } else {
                out.add(p.asText());
            }
        }
        return out;
    }

    private ObjectNode successPing() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("pong", true);
        n.put("connection", "conn-" + connId);
        return n;
    }

    private ObjectNode getPoolStatsResponse() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("connection", "conn-" + connId);

        if (poolManager != null) {
            Map<String, Object> stats = poolManager.getPoolStats();
            for (Map.Entry<String, Object> entry : stats.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Number) {
                    if (value instanceof Integer || value instanceof Long) {
                        n.put(entry.getKey(), ((Number) value).longValue());
                    } else {
                        n.put(entry.getKey(), ((Number) value).doubleValue());
                    }
                } else if (value instanceof String) {
                    n.put(entry.getKey(), (String) value);
                } else {
                    n.put(entry.getKey(), value == null ? "null" : value.toString());
                }
            }
        } else {
            n.put("status", "pool-manager-not-available (likely test mode with injected DataSource)");
        }

        // Include parked transaction info for metrics / monitoring.
        // startTime is the creation time (useful for total lifetime metrics).
        // lastUsed is the last activity time (used by the sweeper for idle timeout).
        ObjectNode txNode = n.putObject("transactions");
        txNode.put("parkedCount", parkedTx.size());
        ArrayNode txArray = txNode.putArray("parked");
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TxContext> entry : parkedTx.entrySet()) {
            ObjectNode t = txArray.addObject();
            t.put("txId", entry.getKey());
            TxContext ctx = entry.getValue();
            t.put("startTime", ctx.startTime);
            t.put("lastUsed", ctx.lastUsed);
            t.put("ageMs", now - ctx.startTime);
            t.put("idleMs", now - ctx.lastUsed);
        }
        return n;
    }

    private void writeResponse(String id, ObjectNode body) {
        if (id != null) body.put("id", id);
        body.put("success", body.has("success") ? body.get("success").asBoolean() : true);
        writeResponseRaw(body);
    }

    private void writeError(String id, String message) {
        writeError(id, message, 0);
    }

    private void writeError(String id, String message, long durationMs) {
        ObjectNode err = MAPPER.createObjectNode();
        if (id != null) err.put("id", id);
        err.put("success", false);
        err.put("error", message != null ? message : "unknown error");
        if (durationMs > 0) err.put("durationMs", durationMs);
        writeResponseRaw(err);
    }

    private synchronized void writeResponseRaw(JsonNode node) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(node);
            out.writeInt(bytes.length);
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            running = false;
            closeQuietly();
        }
    }

    private void closeQuietly() {
        running = false;

        // Cleanup any parked tx connections for this client
        for (Map.Entry<String, TxContext> entry : parkedTx.entrySet()) {
            Connection c = entry.getValue().conn;
            try {
                c.rollback();
                c.setAutoCommit(true);
                c.close();
                log.debug("[Conn-{}] Cleaned up parked tx {} on disconnect", connId, entry.getKey());
            } catch (Exception e) {
                log.warn("[Conn-{}] Error cleaning parked tx {}: {}", connId, entry.getKey(), e.getMessage());
            }
        }
        parkedTx.clear();

        try { if (in != null) in.close(); } catch (Exception ignore) {}
        try { if (out != null) out.close(); } catch (Exception ignore) {}
        try { if (socket != null) socket.close(); } catch (Exception ignore) {}
    }
}
