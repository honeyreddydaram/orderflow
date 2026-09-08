package com.orderflow.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per terminal-event notification. No unique constraint beyond the PK: unlike Payment
 * Service's payments.order_id, nothing looks up a notification by orderId - the only read path is
 * the caller's own paginated list - so there's nothing for a constraint to protect.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String message;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Notification create(UUID orderId, UUID userId, NotificationType type, String subject, String message) {
        Notification notification = new Notification();
        notification.id = UUID.randomUUID();
        notification.orderId = orderId;
        notification.userId = userId;
        notification.type = type;
        notification.subject = subject;
        notification.message = message;
        return notification;
    }
}
