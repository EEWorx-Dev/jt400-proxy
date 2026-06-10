package dev.eeworx.mb.utils.jt400proxy;

import dev.eeworx.mb.utils.jt400proxy.config.ProxyConfig;
import dev.eeworx.mb.utils.jt400proxy.db.HikariPoolManager;
import dev.eeworx.mb.utils.jt400proxy.net.FramedDuplexServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main entry point for the jt400 proxy server.
 * Responsibilities:
 *  - Load configuration (env + fallbacks)
 *  - Initialize HikariCP pool backed by jt400 JDBC
 *  - Start the framed duplex TCP server (single port, persistent full-duplex client connections)
 *  - Handle graceful shutdown
 */
public class Jt400ProxyServer {

    private static final Logger log = LoggerFactory.getLogger(Jt400ProxyServer.class);
    private static final AtomicBoolean running = new AtomicBoolean(true);

    public static void main(String[] args) {
        log.info("========================================");
        log.info("jt400-proxy-server {}", getVersion());
        log.info("========================================");

        ProxyConfig config;
        try {
            config = ProxyConfig.load();
            log.info("Config loaded. AS400 host={} db={} tcpPort={} hikariMaxPool={}",
                    config.getHost(), config.getDatabase(), config.getTcpPort(), config.getHikariMaxPoolSize());
        } catch (Exception e) {
            log.error("FATAL: Failed to load configuration: {}", e.getMessage());
            log.error("Required env: AS400_HOST, AS400_USER, AS400_PASSWORD (or provide via other means).");
            System.exit(1);
            return;
        }

        HikariPoolManager poolManager = null;
        FramedDuplexServer server = null;

        try {
            // Initialize the real connection pool (this is the heart of the stability fix)
            poolManager = new HikariPoolManager(config);
            try {
                poolManager.init();
            } catch (Exception poolErr) {
                System.err.println("[WARN] Pool initialization failed (expected if no AS/400 here): " + poolErr.getMessage());
                System.err.println("[WARN] TCP endpoint will still start; queries will return errors until the pool is healthy.");
            }

            // Start the TCP duplex endpoint (this is what the Node client connects to)
            server = new FramedDuplexServer(config, poolManager);
            server.start();

            final HikariPoolManager pmForHook = poolManager;
            final FramedDuplexServer srvForHook = server;

            log.info("========================================");
            log.info("jt400-proxy-server is READY.");
            log.info("TCP endpoint (duplex framed JSON): {}:{}", config.getTcpHost(), config.getTcpPort());
            log.info("Press Ctrl-C to shutdown.");
            log.info("========================================");

            // Register shutdown hook for clean pool close
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutdown signal received...");
                running.set(false);
                shutdown(pmForHook, srvForHook);
            }, "jt400-proxy-shutdown"));

            // Block until shutdown
            while (running.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

        } catch (Exception e) {
            log.error("FATAL startup error: {}", e.getMessage(), e);
            shutdown(poolManager, server);
            System.exit(1);
        }
    }

    private static void shutdown(HikariPoolManager pool, FramedDuplexServer srv) {
        try {
            if (srv != null) {
                srv.stop();
            }
        } catch (Exception ignore) {}
        try {
            if (pool != null) {
                pool.close();
            }
        } catch (Exception ignore) {}
        System.out.println("jt400-proxy-server stopped.");
    }

    private static String getVersion() {
        // In a real build this could come from the jar manifest or a generated class.
        Package pkg = Jt400ProxyServer.class.getPackage();
        return (pkg != null && pkg.getImplementationVersion() != null)
                ? pkg.getImplementationVersion()
                : "0.1.0-dev";
    }
}
