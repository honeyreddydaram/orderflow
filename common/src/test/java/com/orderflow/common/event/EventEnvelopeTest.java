package com.orderflow.common.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

    private record Payload(String value) {
    }

    @Test
    void of_generatesIdAndTimestamp() {
        UUID correlationId = UUID.randomUUID();
        EventEnvelope<Payload> envelope = EventEnvelope.of("TestEvent", correlationId, new Payload("x"));

        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.eventType()).isEqualTo("TestEvent");
        assertThat(envelope.correlationId()).isEqualTo(correlationId);
        assertThat(envelope.occurredAt()).isNotNull();
        assertThat(envelope.payload().value()).isEqualTo("x");
    }

    @Test
    void constructor_rejectsNullFields() {
        assertThatThrownBy(() -> new EventEnvelope<>(null, "TestEvent", UUID.randomUUID(),
                java.time.Instant.now(), new Payload("x")))
                .isInstanceOf(NullPointerException.class);
    }
}
