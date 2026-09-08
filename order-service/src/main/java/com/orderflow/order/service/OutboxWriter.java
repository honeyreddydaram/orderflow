package com.orderflow.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.order.domain.OutboxEvent;
import com.orderflow.order.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Writes an outbox row in the caller's existing transaction (default REQUIRED propagation) - the
 * write is only ever durable if the surrounding business change also commits. See
 * docs/architecture.md section 12.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public <T> void write(UUID aggregateId, String eventType, String topic, T payload, UUID correlationId) {
        EventEnvelope<T> envelope = EventEnvelope.of(eventType, correlationId, payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize " + eventType + " event", e);
        }
        outboxEventRepository.save(OutboxEvent.create(aggregateId, eventType, topic, json, correlationId));
    }
}
