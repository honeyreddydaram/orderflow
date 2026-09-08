package com.orderflow.inventory.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Explicitly provisions every topic (and its dead-letter topic) this service produces to or
 * consumes from, rather than relying solely on the broker's auto-create setting - see
 * docs/architecture.md sections 5/11. NewTopic bean declarations are idempotent, so this
 * intentionally re-declares topics Order Service already provisioned (order.created,
 * payment.completed, payment.failed) - Inventory Service must be able to start up correctly
 * regardless of whether Order Service has run first.
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

    // Consumed by this service
    @Bean
    public NewTopic orderCreatedTopic() {
        return topic("order.created");
    }

    @Bean
    public NewTopic orderCreatedDlt() {
        return topic("order.created.DLT");
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

    // Produced by this service
    @Bean
    public NewTopic inventoryReservedTopic() {
        return topic("inventory.reserved");
    }

    @Bean
    public NewTopic inventoryReservationFailedTopic() {
        return topic("inventory.reservation-failed");
    }
}
