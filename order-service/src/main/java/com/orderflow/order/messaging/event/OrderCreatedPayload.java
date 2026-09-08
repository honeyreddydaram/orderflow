package com.orderflow.order.messaging.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderCreatedPayload(UUID orderId, UUID userId, List<Item> items, BigDecimal totalAmount) {

    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {
    }
}
