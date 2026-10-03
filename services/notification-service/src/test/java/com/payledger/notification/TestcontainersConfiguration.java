package com.payledger.notification;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String TOPIC = "payledger.transfers";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.3.1");
    }

    /** Owned by the core service in production; created here because the core does not run in these tests. */
    @Bean
    NewTopic transfersTopic() {
        return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
    }

    /** Plays the core service's JWK Set endpoint. */
    @Bean(destroyMethod = "close")
    TestJwtIssuer testJwtIssuer() throws Exception {
        return new TestJwtIssuer();
    }

    @Bean
    DynamicPropertyRegistrar jwkSetUri(TestJwtIssuer issuer) {
        return registry -> registry.add("payledger.security.jwt.jwk-set-uri", issuer::jwksUri);
    }
}
