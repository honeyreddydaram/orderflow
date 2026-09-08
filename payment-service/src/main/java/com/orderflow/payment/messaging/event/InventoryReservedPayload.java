package com.orderflow.payment.messaging.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InventoryReservedPayload(UUID orderId, UUID reservationId, UUID userId, BigDecimal totalAmount,
                                        List<Item> items) {

    public record Item(UUID productId, int quantity) {
    }
}
