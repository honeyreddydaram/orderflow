package com.orderflow.order.messaging.event;

import java.util.List;
import java.util.UUID;

public record InventoryReservationFailedPayload(UUID orderId, String reason, List<Item> items) {

    public record Item(UUID productId, int requestedQty, int availableQty) {
    }
}
