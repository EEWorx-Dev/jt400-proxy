package dev.eeworx.mb.utils.jt400proxy.db;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryProcessorMappingTest {

    private DataSource dataSource;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jt400test;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        this.dataSource = ds;

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS TEST_TABLE");
            st.execute("CREATE TABLE TEST_TABLE (ID INT, NAME VARCHAR(100), BALANCE DECIMAL(10,2), ACTIVE BOOLEAN)");
            st.execute("INSERT INTO TEST_TABLE VALUES (1, 'Alice', 1234.56, TRUE)");
            st.execute("INSERT INTO TEST_TABLE VALUES (2, 'Bob', 99.99, FALSE)");
        }
    }

    @Test
    void selectReturnsRowsAsListOfMaps() {
        QueryProcessor processor = new QueryProcessor(dataSource);

        QueryProcessor.Result result = processor.execute("SELECT ID, NAME, BALANCE, ACTIVE FROM TEST_TABLE ORDER BY ID", List.of());

        assertThat(result.success).isTrue();
        assertThat(result.data).hasSize(2);

        Map<String, Object> first = result.data.get(0);
        // H2 tends to return Integer for small INTs; real jt400 often returns Long.
        // We only care that the numeric value is correct.
        assertThat(((Number) first.get("ID")).intValue()).isEqualTo(1);
        assertThat(first.get("NAME")).isEqualTo("Alice");
        // Decimal comes back as BigDecimal or Double depending on driver; we accept either in mapping
        assertThat(first.get("BALANCE").toString()).contains("1234.56");
        assertThat(first.get("ACTIVE")).isEqualTo(true);
    }

    @Test
    void updateReturnsAffectedRows() {
        QueryProcessor processor = new QueryProcessor(dataSource);

        QueryProcessor.Result result = processor.execute(
                "UPDATE TEST_TABLE SET BALANCE = BALANCE + ? WHERE ID = ?",
                List.of(10.0, 1)
        );

        assertThat(result.success).isTrue();
        assertThat(result.affectedRows).isEqualTo(1);
        assertThat(result.data).isNull();
    }

    @Test
    void badSqlReturnsFailureWithMessage() {
        QueryProcessor processor = new QueryProcessor(dataSource);

        QueryProcessor.Result result = processor.execute("SELECT * FROM NON_EXISTENT_TABLE", List.of());

        assertThat(result.success).isFalse();
        assertThat(result.error).isNotBlank();
    }
}
