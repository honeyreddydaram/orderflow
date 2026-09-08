package com.orderflow.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox row: written in the same local transaction as the business change it
 * describes, published later by {@code OutboxPoller}. See docs/architecture.md section 12.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public static OutboxEvent create(UUID aggregateId, String eventType, String topic, String payload, UUID correlationId) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID();
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.topic = topic;
        event.payload = payload;
        event.correlationId = correlationId;
        event.createdAt = Instant.now();
        return event;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
