package com.orderflow.order.repository;

import com.orderflow.order.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Conflict-safe upsert: returns 1 if this is the first time {@code eventId} has been seen,
     * 0 if it was already recorded. Never throws for a duplicate - a plain save() would raise
     * DataIntegrityViolationException on the unique constraint and abort the whole surrounding
     * transaction, which is exactly what this avoids.
     */
    @Modifying
    @Query(value = "INSERT INTO processed_events (id, event_id, processed_at) "
            + "VALUES (:id, :eventId, now()) ON CONFLICT (event_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventId") UUID eventId);
}
