package com.orderflow.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Marks a consumed Kafka event as handled, keyed on the event's own id (unique). Rows are written
 * via {@code ProcessedEventRepository.insertIfAbsent}, a conflict-safe native upsert - never via a
 * plain save/exception path - so a duplicate delivery can never poison the surrounding transaction.
 * The insert always happens IN THE SAME transaction as the business outcome it marks (the
 * Notification row write) - never as a separate up-front step - so a crash between "marked
 * processed" and "business work done" is impossible to observe: either both commit together, or
 * neither does. See docs/architecture.md section 10.
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
