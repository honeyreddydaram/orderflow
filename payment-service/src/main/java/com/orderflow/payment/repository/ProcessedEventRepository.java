package com.orderflow.payment.repository;

import com.orderflow.payment.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Conflict-safe upsert: returns 1 the first time {@code eventId} is seen, 0 if already
     * recorded. Never throws for a duplicate. Callers must invoke this from WITHIN the same
     * transaction as the business work it guards - never as a separate up-front step - so a
     * rollback of that transaction also undoes this insert, and a crash before commit can never
     * leave an event marked processed with no corresponding business effect.
     */
    @Modifying
    @Query(value = "INSERT INTO processed_events (id, event_id, processed_at) "
            + "VALUES (:id, :eventId, now()) ON CONFLICT (event_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventId") UUID eventId);
}
