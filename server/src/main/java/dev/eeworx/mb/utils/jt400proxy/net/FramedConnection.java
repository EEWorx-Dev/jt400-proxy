package dev.eeworx.mb.utils.jt400proxy.net;

import dev.eeworx.mb.utils.jt400proxy.db.HikariPoolManager;
import dev.eeworx.mb.utils.jt400proxy.db.QueryProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

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

    private final Socket socket;
    private final HikariPoolManager poolManager;
    private final QueryProcessor queryProcessor;
    private final ExecutorService queryExecutor;
    private final long connId;
    private volatile boolean running = true;

    private DataInputStream in;
    private DataOutputStream out;

    public FramedConnection(Socket socket, HikariPoolManager poolManager, QueryProcessor queryProcessor, ExecutorService queryExecutor) {
        this.socket = socket;
        this.poolManager = poolManager;
        this.queryProcessor = queryProcessor;
        this.queryExecutor = queryExecutor;
        this.connId = CONN_ID_SEQ.getAndIncrement();
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

            if (sql.isBlank()) {
                writeError(id, "Missing or empty 'sql'");
                return;
            }

            QueryProcessor.Result result = queryProcessor.execute(sql, params);

            ObjectNode resp = result.toJson(MAPPER);
            resp.put("id", id);
            resp.put("op", op);
            writeResponseRaw(resp);
        } catch (Exception e) {
            writeError(id, e.getMessage() != null ? e.getMessage() : e.toString());
        }
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
        try { if (in != null) in.close(); } catch (Exception ignore) {}
        try { if (out != null) out.close(); } catch (Exception ignore) {}
        try { if (socket != null) socket.close(); } catch (Exception ignore) {}
    }
}
