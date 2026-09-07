package com.orderflow.common.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Common envelope wrapping every domain event published to Kafka. The envelope carries
 * identity/tracing metadata; {@code payload} carries the event-specific data.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        UUID correlationId,
        Instant occurredAt,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
    }

    public static <T> EventEnvelope<T> of(String eventType, UUID correlationId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, correlationId, Instant.now(), payload);
    }
}
