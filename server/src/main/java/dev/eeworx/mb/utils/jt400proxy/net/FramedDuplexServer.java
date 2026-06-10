package dev.eeworx.mb.utils.jt400proxy.net;

import dev.eeworx.mb.utils.jt400proxy.config.ProxyConfig;
import dev.eeworx.mb.utils.jt400proxy.db.HikariPoolManager;
import dev.eeworx.mb.utils.jt400proxy.db.QueryProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens on a single TCP port and accepts persistent full-duplex client connections.
 * Each accepted Socket is handed to a FramedConnection that speaks the length-prefixed JSON protocol.
 *
 * This design gives us "duplex" (clients can have many in-flight requests over one socket via id correlation)
 * while the Java side draws real DB connections from a small shared Hikari pool.
 */
public class FramedDuplexServer {

    private static final Logger log = LoggerFactory.getLogger(FramedDuplexServer.class);

    private final ProxyConfig config;
    private final HikariPoolManager poolManager;
    private final QueryProcessor queryProcessor;
    private ExecutorService connectionExecutor;   // for per-client IO handlers
    private ExecutorService queryExecutor;        // for actual JDBC work (keeps IO threads responsive)
    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FramedDuplexServer(ProxyConfig config, HikariPoolManager poolManager) {
        this.config = config;
        this.poolManager = poolManager;
        this.queryProcessor = new QueryProcessor(poolManager);
        initExecutors();
    }

    /**
     * Test-friendly constructor: allows injecting a QueryProcessor backed by any DataSource
     * (e.g. H2 for contract tests, or a real test pool). No HikariPoolManager required.
     */
    public FramedDuplexServer(ProxyConfig config, QueryProcessor queryProcessor) {
        this.config = config;
        this.poolManager = null;
        this.queryProcessor = queryProcessor;
        initExecutors();
    }

    private void initExecutors() {
        // Bounded pools — tune via config later if needed
        this.connectionExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "jt400-conn-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        int poolSizeForSizing = (poolManager != null)
                ? config.getHikariMaxPoolSize()
                : 8;  // reasonable default when using injected processor
        this.queryExecutor = Executors.newFixedThreadPool(
                Math.max(8, poolSizeForSizing * 2),
                r -> {
                    Thread t = new Thread(r, "jt400-query-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                }
        );
    }

    public void start() throws IOException {
        if (running.get()) return;

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.getTcpHost(), config.getTcpPort()));

        running.set(true);

        Thread acceptor = new Thread(() -> {
            log.info("[TCP] Listening on {}:{}", config.getTcpHost(), config.getTcpPort());
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    client.setKeepAlive(true);

                    FramedConnection conn = new FramedConnection(client, poolManager, queryProcessor, queryExecutor);
                    connectionExecutor.submit(conn);
                } catch (IOException e) {
                    if (running.get()) {
                        log.warn("[TCP] Accept error: {}", e.getMessage());
                    }
                }
            }
        }, "jt400-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * Returns the actual TCP port the server is listening on.
     * Useful in tests when using port 0 (ephemeral port).
     */
    public int getBoundPort() {
        if (serverSocket != null && serverSocket.isBound() && !serverSocket.isClosed()) {
            return serverSocket.getLocalPort();
        }
        return config.getTcpPort();
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignore) {}

        // Best effort shutdown of executors
        shutdownExecutor(connectionExecutor, "connection");
        shutdownExecutor(queryExecutor, "query");

        log.info("[TCP] Server stopped.");
    }

    private void shutdownExecutor(ExecutorService exec, String name) {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(3, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
