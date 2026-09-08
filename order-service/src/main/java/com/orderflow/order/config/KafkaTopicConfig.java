package com.orderflow.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Explicitly provisions every topic (and its dead-letter topic) this service produces to or
 * consumes from, rather than relying solely on the broker's auto-create setting - see
 * docs/architecture.md sections 5/11. Spring Boot's auto-configured {@code KafkaAdmin} creates
 * any {@link NewTopic} bean found in the context at startup; re-declaring the same name/config is
 * idempotent, so Inventory/Payment Service can declare these same beans later without conflict.
 *
 * Partition count is a local-dev default; replication factor defaults to 1 for the single-broker
 * local/CI KRaft cluster and is overridable via {@code orderflow.kafka.replication-factor} for a
 * multi-broker deployment.
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

    // Produced by this service
    @Bean
    public NewTopic orderCreatedTopic() {
        return topic("order.created");
    }

    @Bean
    public NewTopic orderConfirmedTopic() {
        return topic("order.confirmed");
    }

    @Bean
    public NewTopic orderFailedTopic() {
        return topic("order.failed");
    }

    // Consumed by this service (owned by Inventory/Payment Service once they exist)
    @Bean
    public NewTopic inventoryReservedTopic() {
        return topic("inventory.reserved");
    }

    @Bean
    public NewTopic inventoryReservedDlt() {
        return topic("inventory.reserved.DLT");
    }

    @Bean
    public NewTopic inventoryReservationFailedTopic() {
        return topic("inventory.reservation-failed");
    }

    @Bean
    public NewTopic inventoryReservationFailedDlt() {
        return topic("inventory.reservation-failed.DLT");
    }

    @Bean
    public NewTopic paymentCompletedTopic() {
        return topic("payment.completed");
    }

    @Bean
    public NewTopic paymentCompletedDlt() {
        return topic("payment.completed.DLT");
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return topic("payment.failed");
    }

    @Bean
    public NewTopic paymentFailedDlt() {
        return topic("payment.failed.DLT");
    }
}
