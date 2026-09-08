package com.orderflow.inventory.messaging.event;

import java.util.List;
import java.util.UUID;

public record InventoryReservedPayload(UUID orderId, UUID reservationId, List<Item> items) {

    public record Item(UUID productId, int quantity) {
    }
}
