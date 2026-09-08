package com.orderflow.inventory.messaging.event;

import java.util.UUID;

public record PaymentFailedPayload(UUID orderId, String reason) {
}
