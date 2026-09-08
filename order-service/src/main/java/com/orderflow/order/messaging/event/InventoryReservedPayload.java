package com.orderflow.order.messaging.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * userId/totalAmount were added so Payment Service (which also consumes this event) can charge and
 * enforce ownership without a synchronous call back to Order Service - Order Service ignores both,
 * since its own Order entity already has this data, but the record shape must match what Inventory
 * Service now publishes or Jackson's default FAIL_ON_UNKNOWN_PROPERTIES sends every message here to
 * the DLT.
 */
public record InventoryReservedPayload(UUID orderId, UUID reservationId, UUID userId, BigDecimal totalAmount,
                                        List<Item> items) {

    public record Item(UUID productId, int quantity) {
    }
}
