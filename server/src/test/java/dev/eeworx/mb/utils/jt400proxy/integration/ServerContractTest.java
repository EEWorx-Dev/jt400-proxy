package dev.eeworx.mb.utils.jt400proxy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eeworx.mb.utils.jt400proxy.config.ProxyConfig;
import dev.eeworx.mb.utils.jt400proxy.db.QueryProcessor;
import dev.eeworx.mb.utils.jt400proxy.net.FramedDuplexServer;
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
import java.util.List;

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

        server = new FramedDuplexServer(config, qp);
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

    // Simple request POJOs for the test (the real protocol just uses plain JSON objects)
    static class PingReq {
        public String id;
        public String op = "ping";
        PingReq(String id) { this.id = id; }
    }

    static class QueryReq {
        public String id;
        public String op = "query";
        public String sql;
        public List<Object> params;
        QueryReq(String id, String sql, List<Object> params) {
            this.id = id; this.sql = sql; this.params = params;
        }
    }
}
