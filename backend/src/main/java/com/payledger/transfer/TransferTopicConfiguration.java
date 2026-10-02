package com.payledger.transfer;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Creates the transfer topic at startup if it does not exist. The broker itself has topic auto-creation
 * disabled, so a typo in a topic name fails loudly instead of creating a stray topic. In production, topics are
 * usually provisioned by infrastructure code (Terraform, Strimzi {@code KafkaTopic}) rather than by the app.
 */
@Configuration(proxyBeanMethods = false)
class TransferTopicConfiguration {

    @Bean
    NewTopic transfersTopic(@Value("${payledger.kafka.topics.transfers.partitions:3}") int partitions,
                            @Value("${payledger.kafka.topics.transfers.replicas:1}") short replicas) {
        return TopicBuilder.name(TransferEvents.TOPIC).partitions(partitions).replicas(replicas).build();
    }
}
