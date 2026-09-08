package com.orderflow.notification.dto;

import com.orderflow.notification.domain.Notification;
import com.orderflow.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID orderId,
        NotificationType type,
        String subject,
        String message,
        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getOrderId(), notification.getType(),
                notification.getSubject(), notification.getMessage(), notification.getCreatedAt());
    }
}
