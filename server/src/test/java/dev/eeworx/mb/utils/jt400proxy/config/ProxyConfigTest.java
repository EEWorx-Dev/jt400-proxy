package dev.eeworx.mb.utils.jt400proxy.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyConfigTest {

    @Test
    void buildsJdbcUrlWithBasicProperties() {
        // Simulate env via system properties for the test (ProxyConfig reads getenv then getProperty)
        System.setProperty("AS400_HOST", "myas400.example.com");
        System.setProperty("AS400_USER", "tester");
        System.setProperty("AS400_PASSWORD", "secret");
        System.setProperty("AS400_DATABASE", "MYLIB");
        System.setProperty("AS400_JDBC_PROPS", "translate binary=true");

        ProxyConfig config = ProxyConfig.load();

        String url = config.buildJdbcUrl();

        assertThat(url)
                .startsWith("jdbc:as400://myas400.example.com/MYLIB;")
                .contains("user=tester")
                .contains("password=secret")
                .contains("translate binary=true")
                .contains("prompt=false")
                .contains("tcp no delay=true");

        // cleanup
        System.clearProperty("AS400_HOST");
        System.clearProperty("AS400_USER");
        System.clearProperty("AS400_PASSWORD");
        System.clearProperty("AS400_DATABASE");
        System.clearProperty("AS400_JDBC_PROPS");
    }

    @Test
    void hasSensibleDefaults() {
        // When no properties are set, it will fail on required fields, but we can test the builder path
        ProxyConfig config = new ProxyConfig.Builder()
                .host("host")
                .user("u")
                .password("p")
                .tcpPort(1234)
                .hikariMaxPoolSize(7)
                .build();

        assertThat(config.getTcpPort()).isEqualTo(1234);
        assertThat(config.getHikariMaxPoolSize()).isEqualTo(7);
        assertThat(config.getHikariConnectionTestQuery()).isEqualTo("SELECT 1 FROM SYSIBM.SYSDUMMY1");
    }
}
