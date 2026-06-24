package dev.eeworx.utils.jt400.proxy.db;

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

            st.execute("DROP TABLE IF EXISTS CHAR_TEST");
            st.execute("CREATE TABLE CHAR_TEST (CODE CHAR(10), DESC CHAR(20))");
            st.execute("INSERT INTO CHAR_TEST VALUES ('FOO', 'Bar with space   ')");
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

    @Test
    void trimStringsOnlyAffectsCharColumnsWhenFlagEnabled() {
        // Default (false) - no trimming
        QueryProcessor noTrim = new QueryProcessor(dataSource);
        QueryProcessor.Result r1 = noTrim.execute("SELECT CODE, DESC FROM CHAR_TEST", List.of());
        assertThat(r1.success).isTrue();
        assertThat((String) r1.data.get(0).get("CODE")).isEqualTo("FOO       "); // CHAR(10) padded
        assertThat((String) r1.data.get(0).get("DESC")).isEqualTo("Bar with space      "); // CHAR(20) padded (14 chars + 6 spaces)

        // With trimStrings=true - only CHAR columns trimmed
        QueryProcessor withTrim = new QueryProcessor(dataSource, true);
        QueryProcessor.Result r2 = withTrim.execute("SELECT CODE, DESC FROM CHAR_TEST", List.of());
        assertThat(r2.success).isTrue();
        assertThat((String) r2.data.get(0).get("CODE")).isEqualTo("FOO"); // right-trimmed
        assertThat((String) r2.data.get(0).get("DESC")).isEqualTo("Bar with space");

        // Also test per-call flag
        QueryProcessor.Result r3 = noTrim.execute("SELECT CODE, DESC FROM CHAR_TEST", List.of(), true);
        assertThat((String) r3.data.get(0).get("CODE")).isEqualTo("FOO");
    }
}
