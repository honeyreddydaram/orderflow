package com.orderflow.inventory.service;

import com.orderflow.inventory.messaging.event.InventoryReservationFailedPayload;
import com.orderflow.inventory.repository.ProcessedEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Records a reservation failure in a fresh transaction, called only after
 * StockReservationWriter.attemptReservation has rolled back. Re-inserting the processed_events
 * marker here (rather than assuming it's already there) is deliberate: the previous attempt's
 * insert was rolled back along with everything else, so this is genuinely the first successful
 * commit for this event.
 */
@Component
public class InventoryReservationFailureWriter {

    private final ProcessedEventRepository processedEventRepository;
    private final OutboxWriter outboxWriter;

    public InventoryReservationFailureWriter(ProcessedEventRepository processedEventRepository, OutboxWriter outboxWriter) {
        this.processedEventRepository = processedEventRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public void recordFailure(UUID eventId, UUID orderId, String reason,
                               List<StockReservationWriter.ItemShortfall> shortfalls, UUID correlationId) {
        int inserted = processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId);
        if (inserted == 0) {
            // Another delivery already recorded this outcome - don't publish a second failure event.
            return;
        }

        List<InventoryReservationFailedPayload.Item> payloadItems = shortfalls.stream()
                .map(s -> new InventoryReservationFailedPayload.Item(s.productId(), s.requestedQty(), s.availableQty()))
                .toList();
        outboxWriter.write(orderId, "InventoryReservationFailed", "inventory.reservation-failed",
                new InventoryReservationFailedPayload(orderId, reason, payloadItems), correlationId);
    }
}
