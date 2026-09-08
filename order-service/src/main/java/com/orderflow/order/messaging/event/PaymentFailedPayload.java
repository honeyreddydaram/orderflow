package com.orderflow.order.messaging.event;

import java.util.UUID;

public record PaymentFailedPayload(UUID orderId, String reason) {
}
