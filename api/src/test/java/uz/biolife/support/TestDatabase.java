package uz.biolife.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One PostgreSQL container shared by every integration test in the JVM. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static { POSTGRES.start(); }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() { return POSTGRES; }
}
