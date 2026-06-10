package dev.eeworx.mb.utils.jt400proxy.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.eeworx.mb.utils.jt400proxy.config.ProxyConfig;
import dev.eeworx.mb.utils.jt400proxy.db.QueryProcessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Modest set of live integration tests against a *real* AS/400.
 *
 * These are opt-in because most environments (including normal CI) will not have
 * network access + credentials to a test LPAR.
 *
 * To run:
 *   AS400_LIVE_TESTS=true mvn test -P live
 *   or
 *   AS400_LIVE_TESTS=true mvn test -Dgroups=live
 *
 * The same environment variables used by the main application are honored
 * (AS400_HOST, AS400_USER, AS400_PASSWORD, AS400_DATABASE, etc.).
 */
@Tag("live")
class LiveAs400Test {

    private static DataSource dataSource;

    @BeforeAll
    static void setup() {
        // Only attempt to run these tests when explicitly enabled
        String enabled = System.getenv("AS400_LIVE_TESTS");
        assumeTrue("true".equalsIgnoreCase(enabled) || "1".equals(enabled),
                "Skipping live AS/400 tests (set AS400_LIVE_TESTS=true to enable)");

        // Load config the same way the real server does
        ProxyConfig config = ProxyConfig.load();

        // Basic sanity: we need at least host + user
        assumeTrue(config.getHost() != null && !config.getHost().isBlank(), "AS400_HOST is required for live tests");
        assumeTrue(config.getUser() != null && !config.getUser().isBlank(), "AS400_USER is required for live tests");

        // Create a small Hikari pool just for the test (mirrors production usage)
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.buildJdbcUrl());
        hc.setDriverClassName("com.ibm.as400.access.AS400JDBCDriver");
        hc.setMaximumPoolSize(3);
        hc.setMinimumIdle(1);
        hc.setConnectionTestQuery(config.getHikariConnectionTestQuery());

        dataSource = new HikariDataSource(hc);
    }

    @Test
    void canSelectFromSysdummy1() {
        QueryProcessor qp = new QueryProcessor(dataSource);

        QueryProcessor.Result result = qp.execute("SELECT 1 AS ONE FROM SYSIBM.SYSDUMMY1", List.of());

        assertThat(result.success).isTrue();
        assertThat(result.data).hasSize(1);
        assertThat(result.data.get(0).get("ONE")).isNotNull();
    }

    @Test
    void canExecuteParameterizedSelect() {
        QueryProcessor qp = new QueryProcessor(dataSource);

        // This query is safe and works on any AS/400
        QueryProcessor.Result result = qp.execute(
                "SELECT ? AS ECHO_STR, ? AS ECHO_INT FROM SYSIBM.SYSDUMMY1",
                List.of("hello", 42)
        );

        assertThat(result.success).isTrue();
        Map<String, Object> row = result.data.get(0);
        assertThat(row.get("ECHO_STR")).isEqualTo("hello");
        assertThat(row.get("ECHO_INT")).isEqualTo(42L);
    }

    @Test
    void badSqlReturnsProperError() {
        QueryProcessor qp = new QueryProcessor(dataSource);

        QueryProcessor.Result result = qp.execute("SELECT * FROM THIS_TABLE_DOES_NOT_EXIST_12345", List.of());

        assertThat(result.success).isFalse();
        assertThat(result.error).isNotBlank();
        // Real DB2 for i will usually give a useful SQLSTATE
    }
}
