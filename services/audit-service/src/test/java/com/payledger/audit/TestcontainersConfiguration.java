package com.payledger.audit;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL and Kafka. As in production, Flyway migrates as the owner (the container's superuser) and the
 * application connects as the restricted {@code audit_app} role, so the privilege tests exercise the real setup.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String APP_ROLE = "audit_app";

    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("audit")
                .withInitScript("create-app-role.sql");
    }

    @Bean
    DynamicPropertyRegistrar auditDatabase(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> APP_ROLE);
            registry.add("spring.datasource.password", () -> APP_ROLE);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
        };
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.3.1");
    }

    /** Owned by the core service in production; created here because the core does not run in these tests. */
    @Bean
    NewTopic transfersTopic() {
        return TopicBuilder.name("payledger.transfers").partitions(3).replicas(1).build();
    }
}
