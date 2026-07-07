package dev.eeworx.utils.jt400.proxy.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a single SQL statement (query or update) using a short-lived pooled Connection.
 * Returns a JSON-friendly result structure ready for the framed protocol.
 *
 * This is where the "returns results as an array of json objects" requirement is implemented.
 */
public class QueryProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HikariPoolManager poolManager;
    private final DataSource dataSource;
    private final boolean trimStrings;

    /**
     * Primary constructor used in production (via Hikari pool manager).
     */
    public QueryProcessor(HikariPoolManager poolManager) {
        this(poolManager, false);
    }

    public QueryProcessor(HikariPoolManager poolManager, boolean trimStrings) {
        this.poolManager = poolManager;
        this.dataSource = null;
        this.trimStrings = trimStrings;
    }

    /**
     * Constructor for testing / contract tests.
     * Allows injecting any DataSource (H2 for unit tests, or a real pooled one for live tests).
     */
    public QueryProcessor(DataSource dataSource) {
        this(dataSource, false);
    }

    public QueryProcessor(DataSource dataSource, boolean trimStrings) {
        this.poolManager = null;
        this.dataSource = dataSource;
        this.trimStrings = trimStrings;
    }

    public boolean isTrimStrings() {
        return trimStrings;
    }

    public static final class Result {
        public final boolean success;
        public final String error;
        public final String sqlState;
        public final Integer errorCode;
        public final List<Map<String, Object>> data;   // for SELECT
        public final Integer affectedRows;             // for DML
        public final long durationMs;
        public final String connectionInfo;

        private Result(boolean success, String error, String sqlState, Integer errorCode,
                       List<Map<String, Object>> data, Integer affectedRows,
                       long durationMs, String connectionInfo) {
            this.success = success;
            this.error = error;
            this.sqlState = sqlState;
            this.errorCode = errorCode;
            this.data = data;
            this.affectedRows = affectedRows;
            this.durationMs = durationMs;
            this.connectionInfo = connectionInfo;
        }

        public static Result successSelect(List<Map<String, Object>> rows, long durationMs, String connInfo) {
            return new Result(true, null, null, null, rows, null, durationMs, connInfo);
        }

        public static Result successUpdate(int affected, long durationMs, String connInfo) {
            return new Result(true, null, null, null, null, affected, durationMs, connInfo);
        }

        public static Result failure(String error, String sqlState, Integer errorCode, long durationMs) {
            return new Result(false, error, sqlState, errorCode, null, null, durationMs, null);
        }

        public ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("success", success);
            n.put("durationMs", durationMs);
            if (connectionInfo != null) n.put("connection", connectionInfo);

            if (!success) {
                n.put("error", error != null ? error : "unknown");
                if (sqlState != null) n.put("sqlState", sqlState);
                if (errorCode != null) n.put("errorCode", errorCode);
                return n;
            }

            if (data != null) {
                ArrayNode arr = mapper.createArrayNode();
                for (Map<String, Object> row : data) {
                    ObjectNode rowNode = mapper.createObjectNode();
                    for (Map.Entry<String, Object> e : row.entrySet()) {
                        Object v = e.getValue();
                        if (v == null) {
                            rowNode.putNull(e.getKey());
                        } else if (v instanceof Number) {
                            if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                                rowNode.put(e.getKey(), ((Number) v).longValue());
                            } else {
                                rowNode.put(e.getKey(), ((Number) v).doubleValue());
                            }
                        } else if (v instanceof Boolean) {
                            rowNode.put(e.getKey(), (Boolean) v);
                        } else {
                            rowNode.put(e.getKey(), v.toString());
                        }
                    }
                    arr.add(rowNode);
                }
                n.set("data", arr);
                n.put("rowCount", data.size());
            } else if (affectedRows != null) {
                n.put("affectedRows", affectedRows);
            }
            return n;
        }
    }

    public Result execute(String sql, List<Object> params) {
        return execute(sql, params, this.trimStrings);
    }

    public Result execute(String sql, List<Object> params, boolean trimStrings) {
        long start = System.nanoTime();
        String connInfo = null;

        try (Connection c = acquireConnection()) {
            connInfo = describeConnection(c);

            // Use Statement.execute + branch on result set vs update count for robustness
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bindParameters(ps, params);

                boolean hasResultSet = ps.execute();

                if (hasResultSet) {
                    try (ResultSet rs = ps.getResultSet()) {
                        List<Map<String, Object>> rows = mapResultSet(rs, trimStrings);
                        long dur = (System.nanoTime() - start) / 1_000_000;
                        return Result.successSelect(rows, dur, connInfo);
                    }
                } else {
                    int updateCount = ps.getUpdateCount();
                    long dur = (System.nanoTime() - start) / 1_000_000;
                    return Result.successUpdate(updateCount, dur, connInfo);
                }
            }
        } catch (SQLException e) {
            long dur = (System.nanoTime() - start) / 1_000_000;
            return Result.failure(
                    e.getMessage(),
                    e.getSQLState(),
                    e.getErrorCode(),
                    dur
            );
        } catch (Exception e) {
            long dur = (System.nanoTime() - start) / 1_000_000;
            return Result.failure(e.getMessage(), null, null, dur);
        }
    }

    /**
     * Execute using a provided Connection (for transactions).
     * The caller is responsible for the Connection lifecycle (commit/rollback/close).
     */
    public Result execute(String sql, List<Object> params, Connection providedConn) {
        return execute(sql, params, providedConn, this.trimStrings);
    }

    public Result execute(String sql, List<Object> params, Connection providedConn, boolean trimStrings) {
        long start = System.nanoTime();
        String connInfo = describeConnection(providedConn);

        try {
            try (PreparedStatement ps = providedConn.prepareStatement(sql)) {
                bindParameters(ps, params);

                boolean hasResultSet = ps.execute();

                if (hasResultSet) {
                    try (ResultSet rs = ps.getResultSet()) {
                        List<Map<String, Object>> rows = mapResultSet(rs, trimStrings);
                        long dur = (System.nanoTime() - start) / 1_000_000;
                        return Result.successSelect(rows, dur, connInfo);
                    }
                } else {
                    int updateCount = ps.getUpdateCount();
                    long dur = (System.nanoTime() - start) / 1_000_000;
                    return Result.successUpdate(updateCount, dur, connInfo);
                }
            }
        } catch (SQLException e) {
            long dur = (System.nanoTime() - start) / 1_000_000;
            return Result.failure(
                    e.getMessage(),
                    e.getSQLState(),
                    e.getErrorCode(),
                    dur
            );
        } catch (Exception e) {
            long dur = (System.nanoTime() - start) / 1_000_000;
            return Result.failure(e.getMessage(), null, null, dur);
        }
    }

    private Connection acquireConnection() throws SQLException {
        if (dataSource != null) {
            return dataSource.getConnection();
        }
        if (poolManager != null) {
            return poolManager.getConnection();
        }
        throw new SQLException("QueryProcessor has no DataSource or HikariPoolManager configured");
    }

    private void bindParameters(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null) return;
        for (int i = 0; i < params.size(); i++) {
            Object val = params.get(i);
            int jdbcIdx = i + 1;

            if (val == null) {
                ps.setNull(jdbcIdx, Types.VARCHAR);
            } else if (val instanceof Integer) {
                ps.setInt(jdbcIdx, (Integer) val);
            } else if (val instanceof Long) {
                ps.setLong(jdbcIdx, (Long) val);
            } else if (val instanceof Double || val instanceof Float) {
                ps.setDouble(jdbcIdx, ((Number) val).doubleValue());
            } else if (val instanceof Boolean) {
                ps.setBoolean(jdbcIdx, (Boolean) val);
            } else if (val instanceof java.util.Date) {
                ps.setTimestamp(jdbcIdx, new Timestamp(((java.util.Date) val).getTime()));
            } else if (val instanceof String) {
                String s = (String) val;
                if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                    // DATE only
                    ps.setDate(jdbcIdx, java.sql.Date.valueOf(s));
                } else if (s.matches("^\\d{4}-\\d{2}-\\d{2}[ T].*")) {
                    // TIMESTAMP-like (ISO or with space)
                    try {
                        String norm = s.replace('T', ' ');
                        ps.setTimestamp(jdbcIdx, Timestamp.valueOf(norm.length() > 19 ? norm.substring(0, 19) : norm));
                    } catch (Exception ignore) {
                        ps.setString(jdbcIdx, s);
                    }
                } else {
                    ps.setString(jdbcIdx, s);
                }
            } else {
                ps.setString(jdbcIdx, val.toString());
            }
        }
    }

    List<Map<String, Object>> mapResultSet(ResultSet rs) throws SQLException {
        return mapResultSet(rs, this.trimStrings);
    }

    List<Map<String, Object>> mapResultSet(ResultSet rs, boolean trimStrings) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> colNames = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            // Use label if available, fall back to name. Trim to be safe.
            String name = meta.getColumnLabel(i);
            if (name == null || name.isBlank()) name = meta.getColumnName(i);
            colNames.add(name != null ? name.trim() : "COL" + i);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                Object val = rs.getObject(i);
                if (trimStrings && val instanceof String s) {
                    int colType = meta.getColumnType(i);
                    if (colType == Types.CHAR || colType == Types.NCHAR) {
                        val = s.stripTrailing();
                    }
                }
                row.put(colNames.get(i - 1), val);
            }
            rows.add(row);
        }
        return rows;
    }

    private String describeConnection(Connection c) {
        try {
            // Best effort diagnostic info (not a real connection id from AS/400)
            return "hikari-conn@" + Integer.toHexString(System.identityHashCode(c));
        } catch (Exception e) {
            return "unknown";
        }
    }
}
