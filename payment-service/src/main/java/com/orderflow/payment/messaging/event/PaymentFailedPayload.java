package com.orderflow.payment.messaging.event;

import java.util.UUID;

public record PaymentFailedPayload(UUID orderId, String reason) {
}
