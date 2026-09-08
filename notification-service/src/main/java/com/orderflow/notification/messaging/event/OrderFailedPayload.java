package com.orderflow.notification.messaging.event;

import java.util.UUID;

public record OrderFailedPayload(UUID orderId, UUID userId, String reason) {
}
