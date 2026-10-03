package com.payledger.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topics the core publishes to (see {@code docs/events.md}), created at startup if they do not exist. The broker
 * itself has topic auto-creation disabled, so a typo in a topic name fails loudly instead of creating a stray topic.
 * In production, topics are usually provisioned by infrastructure code (Terraform, Strimzi {@code KafkaTopic})
 * rather than by the app.
 */
@Configuration(proxyBeanMethods = false)
public class EventTopics {

    /** Money movements: keyed by transfer id. */
    public static final String TRANSFERS = "payledger.transfers";
    /** Account lifecycle (opened, frozen, unfrozen, closed): keyed by account id. */
    public static final String ACCOUNTS = "payledger.accounts";
    /** Sign-ins, lockouts, sessions, users and API keys: keyed by user or API key id. */
    public static final String SECURITY = "payledger.security";

    private final int partitions;
    private final short replicas;

    EventTopics(@Value("${payledger.kafka.topics.partitions:3}") int partitions,
                @Value("${payledger.kafka.topics.replicas:1}") short replicas) {
        this.partitions = partitions;
        this.replicas = replicas;
    }

    @Bean
    NewTopic transfersTopic() {
        return topic(TRANSFERS);
    }

    @Bean
    NewTopic accountsTopic() {
        return topic(ACCOUNTS);
    }

    @Bean
    NewTopic securityTopic() {
        return topic(SECURITY);
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }
}
