package dev.eeworx.mb.utils.jt400proxy.db;

import dev.eeworx.mb.utils.jt400proxy.config.ProxyConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the HikariCP DataSource for AS/400 (jt400) connections.
 * This is the key component that replaces the flaky direct node-jt400 pooling.
 */
public class HikariPoolManager {

    private static final Logger log = LoggerFactory.getLogger(HikariPoolManager.class);

    private final ProxyConfig config;
    private HikariDataSource dataSource;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public HikariPoolManager(ProxyConfig config) {
        this.config = config;
    }

    public synchronized void init() {
        if (initialized.get()) return;

        String jdbcUrl = config.buildJdbcUrl();

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        // jt400 driver is loaded by the URL; explicit driver class name is optional but can help in some classloader scenarios
        hc.setDriverClassName("com.ibm.as400.access.AS400JDBCDriver");

        hc.setMaximumPoolSize(config.getHikariMaxPoolSize());
        hc.setMinimumIdle(config.getHikariMinIdle());
        hc.setConnectionTimeout(config.getHikariConnectionTimeoutMs());
        hc.setIdleTimeout(config.getHikariIdleTimeoutMs());
        hc.setMaxLifetime(config.getHikariMaxLifetimeMs());

        hc.setConnectionTestQuery(config.getHikariConnectionTestQuery());
        hc.setKeepaliveTime(config.getHikariKeepaliveTimeMs());
        hc.setValidationTimeout(config.getHikariValidationTimeoutMs());

        // Hikari will use the connectionTestQuery + keepaliveTime to validate connections.
        // Connections are tested (when needed) before being returned to the application
        // via Hikari's internal borrow/validation logic. This is the recommended pattern
        // instead of a traditional "testOnBorrow" flag.

        hc.setPoolName("jt400-hikari-pool");

        // Recommended for long-lived backend services talking to iSeries
        hc.setLeakDetectionThreshold(60_000); // 60s — helps catch unclosed connections in dev
        hc.setAutoCommit(true); // matches typical fire-and-forget query usage from the proxy

        // Connection init SQL (warm up)
        hc.setInitializationFailTimeout(10_000);

        log.info("Creating pool for {}", sanitizeForLog(jdbcUrl));
        this.dataSource = new HikariDataSource(hc);

        // Warm up a couple of connections and run the test query
        warmUpPool();

        initialized.set(true);
        log.info("Pool initialized. maxPoolSize={}", config.getHikariMaxPoolSize());
    }

    private void warmUpPool() {
        int toWarm = Math.min(3, config.getHikariMaxPoolSize());
        for (int i = 0; i < toWarm; i++) {
            long start = System.nanoTime();
            try (Connection c = dataSource.getConnection();
                 Statement st = c.createStatement()) {
                st.execute(config.getHikariConnectionTestQuery());
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                log.debug("Warmup connection {} acquired+tested in {}ms", i, elapsedMs);
            } catch (SQLException e) {
                log.warn("Warmup connection {} failed: {}", i, e.getMessage());
                // Do not fail the whole startup — Hikari will manage eviction
            }
        }
    }

    /**
     * Acquire a short-lived connection from the pool. Caller must close it (try-with-resources).
     *
     * Explicitly runs the configured test query BEFORE returning the connection to the application.
     * If the test fails, the bad connection is evicted from the pool and we retry with a fresh one
     * (up to the configured number of retries).
     */
    public Connection getConnection() throws SQLException {
        if (!initialized.get() || dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Pool not initialized");
        }

        int maxRetries = config.getHikariConnectionAcquireRetries();
        int attempts = 0;
        SQLException lastException = null;

        while (attempts <= maxRetries) {
            attempts++;
            long attemptStart = System.nanoTime();
            Connection conn = null;

            try {
                conn = dataSource.getConnection();
                long acquireMs = (System.nanoTime() - attemptStart) / 1_000_000;

                // Explicit validation BEFORE returning to the application
                long testStart = System.nanoTime();
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(config.getHikariConnectionTestQuery());
                }
                long testMs = (System.nanoTime() - testStart) / 1_000_000;

                long totalMs = (System.nanoTime() - attemptStart) / 1_000_000;
                log.debug("Connection acquired+validated in {}ms (acquire={}ms, test={}ms) on attempt {}",
                        totalMs, acquireMs, testMs, attempts);

                return conn;

            } catch (Exception ex) {
                lastException = (ex instanceof SQLException) ? (SQLException) ex : new SQLException(ex);

                // Discard the bad connection from the pool altogether by closing it.
                // Hikari will detect the closure of a borrowed connection and evict it
                // from the pool so it is not reused.
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (Exception closeEx) {
                        log.debug("Error while closing bad connection during eviction: {}", closeEx.getMessage());
                    }
                }

                if (attempts > maxRetries) {
                    log.warn("Failed to acquire valid connection after {} attempts ({} retries configured)", attempts, maxRetries);
                    throw new SQLException(
                            "Failed to acquire a valid connection after " + attempts + " attempt(s). " +
                            "The connection pool test query failed on every try. Last error: " + lastException.getMessage(),
                            lastException);
                }

                log.warn("Connection test failed on attempt {} ({} retries left). Evicting bad connection and retrying...",
                        attempts, maxRetries - attempts + 1, lastException);
            }
        }

        // Should never reach here
        throw new SQLException("Failed to acquire valid connection", lastException);
    }

    public boolean isInitialized() {
        return initialized.get() && dataSource != null && !dataSource.isClosed();
    }

    public int getActiveConnections() {
        if (dataSource == null) return -1;
        try {
            return dataSource.getHikariPoolMXBean().getActiveConnections();
        } catch (Exception e) {
            return -1;
        }
    }

    public int getTotalConnections() {
        if (dataSource == null) return -1;
        try {
            return dataSource.getHikariPoolMXBean().getTotalConnections();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Returns detailed HikariCP pool metrics.
     * These can be exposed to monitoring systems (Node /metrics, Prometheus, etc.).
     */
    public Map<String, Object> getPoolStats() {
        if (dataSource == null || dataSource.isClosed()) {
            return Map.of("status", "not_initialized");
        }
        try {
            HikariPoolMXBean mx = dataSource.getHikariPoolMXBean();
            return Map.of(
                "activeConnections", mx.getActiveConnections(),
                "idleConnections", mx.getIdleConnections(),
                "totalConnections", mx.getTotalConnections(),
                "threadsAwaitingConnection", mx.getThreadsAwaitingConnection(),
                "maxPoolSize", dataSource.getMaximumPoolSize(),
                "minIdle", dataSource.getMinimumIdle()
            );
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    public synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            log.info("Closing pool...");
            dataSource.close();
        }
        initialized.set(false);
    }

    private static String sanitizeForLog(String url) {
        // Remove password from logs
        return url.replaceAll("password=[^;]+", "password=***");
    }
}
