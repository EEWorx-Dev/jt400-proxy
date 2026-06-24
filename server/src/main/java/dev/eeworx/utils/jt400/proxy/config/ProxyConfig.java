package dev.eeworx.utils.jt400.proxy.config;

import java.util.Objects;

/**
 * Immutable configuration for the proxy.
 * Loaded primarily from environment variables with sensible defaults.
 * In production prefer env vars (12-factor). A config file loader can be added later.
 */
public final class ProxyConfig {

    // AS400 / JDBC
    private final String host;
    private final String user;
    private final String password;
    private final String database;           // used as the catalog / library list part of the URL
    private final String extraJdbcProps;     // raw ";key=val;..." suffix (without leading ;)

    // Proxy network
    private final String tcpHost;
    private final int tcpPort;

    // Hikari
    private final int hikariMaxPoolSize;
    private final int hikariMinIdle;
    private final long hikariConnectionTimeoutMs;
    private final long hikariIdleTimeoutMs;
    private final long hikariMaxLifetimeMs;
    private final long hikariKeepaliveTimeMs;
    private final long hikariValidationTimeoutMs;
    private final String hikariConnectionTestQuery;
    private final int hikariConnectionAcquireRetries;
    private final long txTimeoutMs;
    private final long txSweeperIntervalMs;
    private final boolean trimStrings;

    private ProxyConfig(Builder b) {
        this.host = Objects.requireNonNull(b.host, "AS400_HOST is required");
        this.user = Objects.requireNonNull(b.user, "AS400_USER is required");
        this.password = Objects.requireNonNull(b.password, "AS400_PASSWORD is required");
        this.database = b.database != null ? b.database : "";
        this.extraJdbcProps = b.extraJdbcProps != null ? b.extraJdbcProps : "";

        this.tcpHost = b.tcpHost != null ? b.tcpHost : "0.0.0.0";
        this.tcpPort = b.tcpPort > 0 ? b.tcpPort : 9400;

        this.hikariMaxPoolSize = b.hikariMaxPoolSize > 0 ? b.hikariMaxPoolSize : 20;
        this.hikariMinIdle = b.hikariMinIdle >= 0 ? b.hikariMinIdle : Math.min(5, hikariMaxPoolSize);
        this.hikariConnectionTimeoutMs = b.hikariConnectionTimeoutMs > 0 ? b.hikariConnectionTimeoutMs : 30_000;
        this.hikariIdleTimeoutMs = b.hikariIdleTimeoutMs > 0 ? b.hikariIdleTimeoutMs : 600_000;
        this.hikariMaxLifetimeMs = b.hikariMaxLifetimeMs > 0 ? b.hikariMaxLifetimeMs : 1_800_000;
        this.hikariKeepaliveTimeMs = b.hikariKeepaliveTimeMs > 0 ? b.hikariKeepaliveTimeMs : 120_000; // 2 minutes
        this.hikariValidationTimeoutMs = b.hikariValidationTimeoutMs > 0 ? b.hikariValidationTimeoutMs : 5_000;
        this.hikariConnectionTestQuery = b.hikariConnectionTestQuery != null && !b.hikariConnectionTestQuery.isBlank()
                ? b.hikariConnectionTestQuery
                : "SELECT 1 FROM SYSIBM.SYSDUMMY1";
        this.hikariConnectionAcquireRetries = b.hikariConnectionAcquireRetries > 0 ? b.hikariConnectionAcquireRetries : 2; // 1 initial + 2 retries = 3 attempts total
        this.txTimeoutMs = b.txTimeoutMs > 0 ? b.txTimeoutMs : 300_000; // 5 minutes
        this.txSweeperIntervalMs = b.txSweeperIntervalMs > 0 ? b.txSweeperIntervalMs : 30_000; // check every 30s
        this.trimStrings = b.trimStrings;
    }

    public static ProxyConfig load() {
        return new Builder()
                .host(env("AS400_HOST", null))
                .user(env("AS400_USER", null))
                .password(env("AS400_PASSWORD", null))
                .database(env("AS400_DATABASE", env("AS400_DB", null)))
                .extraJdbcProps(env("AS400_JDBC_PROPS", null))
                .tcpHost(env("PROXY_TCP_HOST", "0.0.0.0"))
                .tcpPort(intEnv("PROXY_TCP_PORT", 9400))
                .hikariMaxPoolSize(intEnv("HIKARI_MAX_POOL_SIZE", 20))
                .hikariMinIdle(intEnv("HIKARI_MIN_IDLE", 5))
                .hikariConnectionTimeoutMs(longEnv("HIKARI_CONNECTION_TIMEOUT_MS", 30_000))
                .hikariIdleTimeoutMs(longEnv("HIKARI_IDLE_TIMEOUT_MS", 600_000))
                .hikariMaxLifetimeMs(longEnv("HIKARI_MAX_LIFETIME_MS", 1_800_000))
                .hikariKeepaliveTimeMs(longEnv("HIKARI_KEEPALIVE_TIME_MS", 120_000))
                .hikariValidationTimeoutMs(longEnv("HIKARI_VALIDATION_TIMEOUT_MS", 5_000))
                .hikariConnectionTestQuery(env("HIKARI_CONNECTION_TEST_QUERY", null))
                .hikariConnectionAcquireRetries(intEnv("HIKARI_CONNECTION_ACQUIRE_RETRIES", 2))
                .txTimeoutMs(longEnv("TX_TIMEOUT_MS", 300_000))
                .txSweeperIntervalMs(longEnv("TX_SWEEPER_INTERVAL_MS", 30_000))
                .trimStrings(booleanEnv("PROXY_TRIM_STRINGS", false))
                .build();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v;
        // Also allow -D system properties as fallback (useful for testing)
        v = System.getProperty(key);
        return (v != null && !v.isBlank()) ? v : def;
    }

    private static int intEnv(String key, int def) {
        String v = env(key, null);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static long longEnv(String key, long def) {
        String v = env(key, null);
        if (v == null) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean booleanEnv(String key, boolean def) {
        String v = env(key, null);
        if (v == null) return def;
        return "true".equalsIgnoreCase(v.trim());
    }

    // Getters
    public String getHost() { return host; }
    public String getUser() { return user; }
    public String getPassword() { return password; }
    public String getDatabase() { return database; }
    public String getExtraJdbcProps() { return extraJdbcProps; }

    public String getTcpHost() { return tcpHost; }
    public int getTcpPort() { return tcpPort; }

    public int getHikariMaxPoolSize() { return hikariMaxPoolSize; }
    public int getHikariMinIdle() { return hikariMinIdle; }
    public long getHikariConnectionTimeoutMs() { return hikariConnectionTimeoutMs; }
    public long getHikariIdleTimeoutMs() { return hikariIdleTimeoutMs; }
    public long getHikariMaxLifetimeMs() { return hikariMaxLifetimeMs; }
    public long getHikariKeepaliveTimeMs() { return hikariKeepaliveTimeMs; }
    public long getHikariValidationTimeoutMs() { return hikariValidationTimeoutMs; }
    public String getHikariConnectionTestQuery() { return hikariConnectionTestQuery; }
    public int getHikariConnectionAcquireRetries() { return hikariConnectionAcquireRetries; }
    public long getTxTimeoutMs() { return txTimeoutMs; }
    public long getTxSweeperIntervalMs() { return txSweeperIntervalMs; }
    public boolean isTrimStrings() { return trimStrings; }

    /**
     * Build the full JDBC URL for jt400.
     * Example: jdbc:as400://host/db;user=...;password=...;translate binary=true;...
     */
    public String buildJdbcUrl() {
        StringBuilder url = new StringBuilder("jdbc:as400://")
                .append(host);
        if (database != null && !database.isBlank()) {
            url.append("/").append(database);
        }
        url.append(";user=").append(user)
           .append(";password=").append(password);

        // Always add a few stability/performance oriented defaults unless overridden in extra
        String baseExtras = "translate binary=true;prompt=false;tcp no delay=true;";
        String extras = extraJdbcProps != null ? extraJdbcProps : "";

        // naive merge: if the extra already contains one of our keys, let the extra win by appending last
        url.append(";").append(baseExtras);
        if (!extras.isBlank()) {
            if (!extras.startsWith(";")) url.append(";");
            url.append(extras);
        }
        return url.toString();
    }

    public static class Builder {
        private String host, user, password, database, extraJdbcProps;
        private String tcpHost;
        private int tcpPort;
        private int hikariMaxPoolSize, hikariMinIdle;
        private long hikariConnectionTimeoutMs, hikariIdleTimeoutMs, hikariMaxLifetimeMs;
        private long hikariKeepaliveTimeMs;
        private long hikariValidationTimeoutMs;
        private String hikariConnectionTestQuery;
        private int hikariConnectionAcquireRetries;
        private long txTimeoutMs;
        private long txSweeperIntervalMs;
        private boolean trimStrings;

        public Builder host(String v) { this.host = v; return this; }
        public Builder user(String v) { this.user = v; return this; }
        public Builder password(String v) { this.password = v; return this; }
        public Builder database(String v) { this.database = v; return this; }
        public Builder extraJdbcProps(String v) { this.extraJdbcProps = v; return this; }
        public Builder tcpHost(String v) { this.tcpHost = v; return this; }
        public Builder tcpPort(int v) { this.tcpPort = v; return this; }
        public Builder hikariMaxPoolSize(int v) { this.hikariMaxPoolSize = v; return this; }
        public Builder hikariMinIdle(int v) { this.hikariMinIdle = v; return this; }
        public Builder hikariConnectionTimeoutMs(long v) { this.hikariConnectionTimeoutMs = v; return this; }
        public Builder hikariIdleTimeoutMs(long v) { this.hikariIdleTimeoutMs = v; return this; }
        public Builder hikariMaxLifetimeMs(long v) { this.hikariMaxLifetimeMs = v; return this; }
        public Builder hikariKeepaliveTimeMs(long v) { this.hikariKeepaliveTimeMs = v; return this; }
        public Builder hikariValidationTimeoutMs(long v) { this.hikariValidationTimeoutMs = v; return this; }
        public Builder hikariConnectionTestQuery(String v) { this.hikariConnectionTestQuery = v; return this; }
        public Builder hikariConnectionAcquireRetries(int v) { this.hikariConnectionAcquireRetries = v; return this; }
        public Builder txTimeoutMs(long v) { this.txTimeoutMs = v; return this; }
        public Builder txSweeperIntervalMs(long v) { this.txSweeperIntervalMs = v; return this; }
        public Builder trimStrings(boolean v) { this.trimStrings = v; return this; }

        public ProxyConfig build() {
            return new ProxyConfig(this);
        }
    }
}
