package com.orderflow.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Explicitly provisions every topic (and its dead-letter topic) this service consumes from,
 * rather than relying solely on the broker's auto-create setting - see docs/architecture.md
 * sections 5/11. NewTopic bean declarations are idempotent, so this intentionally re-declares
 * topics Order Service already provisioned. Notification Service produces nothing, so - unlike
 * every other service's KafkaTopicConfig - there is no "produced by this service" section here.
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${orderflow.kafka.partitions:3}")
    private int partitions;

    @Value("${orderflow.kafka.replication-factor:1}")
    private short replicationFactor;

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicationFactor).build();
    }

    @Bean
    public NewTopic orderConfirmedTopic() {
        return topic("order.confirmed");
    }

    @Bean
    public NewTopic orderConfirmedDlt() {
        return topic("order.confirmed.DLT");
    }

    @Bean
    public NewTopic orderFailedTopic() {
        return topic("order.failed");
    }

    @Bean
    public NewTopic orderFailedDlt() {
        return topic("order.failed.DLT");
    }
}
