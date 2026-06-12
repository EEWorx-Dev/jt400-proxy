package dev.eeworx.utils.jt400.proxy.integration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eeworx.utils.jt400.proxy.config.ProxyConfig;
import dev.eeworx.utils.jt400.proxy.db.QueryProcessor;
import dev.eeworx.utils.jt400.proxy.net.FramedDuplexServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates using FramedDuplexServer with an injected QueryProcessor (backed by H2 here).
 * This is the "Java-side contract test" capability.
 *
 * A real test suite could drive many queries this way without needing the full main() or a Node client.
 */
class ServerContractTest {

    private FramedDuplexServer server;
    private DataSource dataSource;
    private int port;

    @BeforeEach
    void setup() throws Exception {
        // In-memory DB with some data
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:contract;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS PEOPLE");
            st.execute("CREATE TABLE PEOPLE (ID INT PRIMARY KEY, NAME VARCHAR(100))");
            st.execute("INSERT INTO PEOPLE VALUES (1, 'Alice'), (2, 'Bob')");
        }

        QueryProcessor qp = new QueryProcessor(dataSource);

        // Use ephemeral port
        ProxyConfig config = new ProxyConfig.Builder()
                .host("127.0.0.1")
                .user("test")
                .password("test")
                .tcpHost("127.0.0.1")
                .tcpPort(0)  // let OS assign
                .build();

        // For contract tests we provide a direct Supplier<Connection> backed by the test DataSource.
        // This keeps any test-specific connection acquisition logic out of FramedDuplexServer
        // (and completely out of FramedConnection).
        Supplier<Connection> txConnectionProvider = () -> {
            try {
                return dataSource.getConnection();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to acquire test connection for tx", e);
            }
        };

        server = new FramedDuplexServer(config, qp, txConnectionProvider);
        server.start();
        this.port = server.getBoundPort();
        assertThat(port).isGreaterThan(0);
    }

    @AfterEach
    void teardown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void pingOverRealTcp() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            ObjectMapper mapper = new ObjectMapper();
            byte[] payload = mapper.writeValueAsBytes(new PingReq("ping-1"));
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();

            int len = in.readInt();
            byte[] respBytes = new byte[len];
            in.readFully(respBytes);
            JsonNode resp = mapper.readTree(respBytes);

            assertThat(resp.get("success").asBoolean()).isTrue();
            assertThat(resp.get("pong").asBoolean()).isTrue();
            assertThat(resp.get("id").asText()).isEqualTo("ping-1");
        }
    }

    @Test
    void queryOverRealTcp() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            ObjectMapper mapper = new ObjectMapper();
            byte[] payload = mapper.writeValueAsBytes(new QueryReq("q-42", "SELECT NAME FROM PEOPLE WHERE ID=?", List.of(1)));
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();

            int len = in.readInt();
            byte[] respBytes = new byte[len];
            in.readFully(respBytes);
            JsonNode resp = mapper.readTree(respBytes);

            assertThat(resp.get("success").asBoolean()).isTrue();
            assertThat(resp.get("id").asText()).isEqualTo("q-42");
            assertThat(resp.get("data").size()).isEqualTo(1);
            assertThat(resp.get("data").get(0).get("NAME").asText()).isEqualTo("Alice");
        }
    }

    @Test
    void txBeginQueryCommitOverRealTcp() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            ObjectMapper mapper = new ObjectMapper();

            // 1. Begin tx
            byte[] beginPayload = mapper.writeValueAsBytes(new BeginTxReq("begin-1"));
            out.writeInt(beginPayload.length);
            out.write(beginPayload);
            out.flush();

            int beginLen = in.readInt();
            byte[] beginRespBytes = new byte[beginLen];
            in.readFully(beginRespBytes);
            JsonNode beginResp = mapper.readTree(beginRespBytes);

            assertThat(beginResp.get("success").asBoolean()).isTrue();
            String txId = beginResp.get("txId").asText();
            assertThat(txId).isNotBlank();
            assertThat(beginResp.get("status").asText()).isEqualTo("active");

            // 2. Query using the txId (should use parked connection, update lastUsed)
            QueryReq txQuery = new QueryReq("q-tx-1", "SELECT NAME FROM PEOPLE WHERE ID=?", List.of(1));
            txQuery.txId = txId;
            byte[] queryPayload = mapper.writeValueAsBytes(txQuery);
            out.writeInt(queryPayload.length);
            out.write(queryPayload);
            out.flush();

            int qLen = in.readInt();
            byte[] qRespBytes = new byte[qLen];
            in.readFully(qRespBytes);
            JsonNode qResp = mapper.readTree(qRespBytes);

            assertThat(qResp.get("success").asBoolean()).isTrue();
            assertThat(qResp.get("id").asText()).isEqualTo("q-tx-1");
            assertThat(qResp.get("txId").asText()).isEqualTo(txId);
            assertThat(qResp.get("data").size()).isEqualTo(1);
            assertThat(qResp.get("data").get(0).get("NAME").asText()).isEqualTo("Alice");

            // 3. Commit the tx
            byte[] commitPayload = mapper.writeValueAsBytes(new TxOpReq("commit-1", "commit-tx", txId));
            out.writeInt(commitPayload.length);
            out.write(commitPayload);
            out.flush();

            int cLen = in.readInt();
            byte[] cRespBytes = new byte[cLen];
            in.readFully(cRespBytes);
            JsonNode cResp = mapper.readTree(cRespBytes);

            assertThat(cResp.get("success").asBoolean()).isTrue();
            assertThat(cResp.get("txId").asText()).isEqualTo(txId);
            assertThat(cResp.get("status").asText()).isEqualTo("committed");
        }
    }

    @Test
    void txBeginQueryRollbackOverRealTcp() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            ObjectMapper mapper = new ObjectMapper();

            // Begin
            byte[] beginPayload = mapper.writeValueAsBytes(new BeginTxReq("begin-rb"));
            out.writeInt(beginPayload.length);
            out.write(beginPayload);
            out.flush();

            int beginLen = in.readInt();
            byte[] beginRespBytes = new byte[beginLen];
            in.readFully(beginRespBytes);
            JsonNode beginResp = mapper.readTree(beginRespBytes);
            String txId = beginResp.get("txId").asText();

            // Query under tx
            QueryReq txQuery = new QueryReq("q-rb", "SELECT NAME FROM PEOPLE WHERE ID=?", List.of(2));
            txQuery.txId = txId;
            byte[] queryPayload = mapper.writeValueAsBytes(txQuery);
            out.writeInt(queryPayload.length);
            out.write(queryPayload);
            out.flush();

            int qLen = in.readInt();
            byte[] qRespBytes = new byte[qLen];
            in.readFully(qRespBytes);
            JsonNode qResp = mapper.readTree(qRespBytes);
            assertThat(qResp.get("success").asBoolean()).isTrue();
            assertThat(qResp.get("txId").asText()).isEqualTo(txId);

            // Rollback
            byte[] rbPayload = mapper.writeValueAsBytes(new TxOpReq("rb-1", "rollback-tx", txId));
            out.writeInt(rbPayload.length);
            out.write(rbPayload);
            out.flush();

            int rbLen = in.readInt();
            byte[] rbRespBytes = new byte[rbLen];
            in.readFully(rbRespBytes);
            JsonNode rbResp = mapper.readTree(rbRespBytes);

            assertThat(rbResp.get("success").asBoolean()).isTrue();
            assertThat(rbResp.get("txId").asText()).isEqualTo(txId);
            assertThat(rbResp.get("status").asText()).isEqualTo("rolled_back");
        }
    }

    @Test
    void poolStatsIncludesTransactionsWithStartTimeAndLastUsed() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            ObjectMapper mapper = new ObjectMapper();

            // Start a tx so there is something parked
            byte[] beginPayload = mapper.writeValueAsBytes(new BeginTxReq("begin-stats"));
            out.writeInt(beginPayload.length);
            out.write(beginPayload);
            out.flush();

            int beginLen = in.readInt();
            byte[] beginRespBytes = new byte[beginLen];
            in.readFully(beginRespBytes);
            JsonNode beginResp = mapper.readTree(beginRespBytes);
            String txId = beginResp.get("txId").asText();

            // Do a tx-scoped query (should touch lastUsed)
            QueryReq txQuery = new QueryReq("q-stats", "SELECT 1", List.of());
            txQuery.txId = txId;
            byte[] qPayload = mapper.writeValueAsBytes(txQuery);
            out.writeInt(qPayload.length);
            out.write(qPayload);
            out.flush();
            // drain response
            int qLen = in.readInt();
            byte[] ignore = new byte[qLen];
            in.readFully(ignore);

            // Request stats
            byte[] statsPayload = mapper.writeValueAsBytes(new StatsReq("stats-1"));
            out.writeInt(statsPayload.length);
            out.write(statsPayload);
            out.flush();

            int sLen = in.readInt();
            byte[] sRespBytes = new byte[sLen];
            in.readFully(sRespBytes);
            JsonNode sResp = mapper.readTree(sRespBytes);

            assertThat(sResp.get("success").asBoolean()).isTrue();
            JsonNode txs = sResp.get("transactions");
            assertThat(txs).isNotNull();
            assertThat(txs.get("parkedCount").asInt()).isEqualTo(1);

            JsonNode parked = txs.get("parked").get(0);
            assertThat(parked.get("txId").asText()).isEqualTo(txId);
            assertThat(parked.has("startTime")).isTrue();
            assertThat(parked.has("lastUsed")).isTrue();
            assertThat(parked.has("ageMs")).isTrue();
            assertThat(parked.has("idleMs")).isTrue();

            // After a query, lastUsed should be >= startTime (last-activity model)
            long start = parked.get("startTime").asLong();
            long last = parked.get("lastUsed").asLong();
            assertThat(last).isGreaterThanOrEqualTo(start);
        }
    }

    // Simple request POJOs for the test (the real protocol just uses plain JSON objects)
    static class PingReq {
        public String id;
        public String op = "ping";
        PingReq(String id) { this.id = id; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class QueryReq {
        public String id;
        public String op = "query";
        public String sql;
        public List<Object> params;
        public String txId; // optional, for tx-scoped queries
        QueryReq(String id, String sql, List<Object> params) {
            this.id = id; this.sql = sql; this.params = params;
        }
    }

    static class BeginTxReq {
        public String id;
        public String op = "begin-tx";
        BeginTxReq(String id) { this.id = id; }
    }

    static class TxOpReq {
        public String id;
        public String op;
        public String txId;
        TxOpReq(String id, String op, String txId) {
            this.id = id; this.op = op; this.txId = txId;
        }
    }

    static class StatsReq {
        public String id;
        public String op = "pool-stats";
        StatsReq(String id) { this.id = id; }
    }
}
