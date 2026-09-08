package com.orderflow.order.messaging.event;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderConfirmedPayload(UUID orderId, UUID userId, BigDecimal totalAmount) {
}
