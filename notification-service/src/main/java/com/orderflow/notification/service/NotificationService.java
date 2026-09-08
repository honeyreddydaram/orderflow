package com.orderflow.notification.service;

import com.orderflow.common.event.EventEnvelope;
import com.orderflow.notification.domain.Notification;
import com.orderflow.notification.domain.NotificationType;
import com.orderflow.notification.dto.NotificationResponse;
import com.orderflow.notification.messaging.event.OrderConfirmedPayload;
import com.orderflow.notification.messaging.event.OrderFailedPayload;
import com.orderflow.notification.repository.NotificationRepository;
import com.orderflow.notification.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final NotificationSender notificationSender;

    public NotificationService(NotificationRepository notificationRepository,
                                ProcessedEventRepository processedEventRepository,
                                NotificationSender notificationSender) {
        this.notificationRepository = notificationRepository;
        this.processedEventRepository = processedEventRepository;
        this.notificationSender = notificationSender;
    }

    // ---- REST-facing -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<NotificationResponse> listForUser(UUID userId, Pageable pageable) {
        return notificationRepository.findAllByUserId(userId, pageable).map(NotificationResponse::from);
    }

    // ---- Saga event handlers ------------------------------------------------------------------

    @Transactional
    public void handleOrderConfirmed(EventEnvelope<OrderConfirmedPayload> envelope) {
        if (!firstDelivery(envelope.eventId())) {
            return;
        }
        OrderConfirmedPayload payload = envelope.payload();
        String subject = "Your order has been confirmed";
        String message = "Order " + payload.orderId() + " has been confirmed. Total: " + payload.totalAmount() + ".";
        Notification notification = Notification.create(payload.orderId(), payload.userId(),
                NotificationType.ORDER_CONFIRMED, subject, message);
        notificationRepository.save(notification);
        notificationSender.send(notification, envelope.correlationId());
    }

    @Transactional
    public void handleOrderFailed(EventEnvelope<OrderFailedPayload> envelope) {
        if (!firstDelivery(envelope.eventId())) {
            return;
        }
        OrderFailedPayload payload = envelope.payload();
        String subject = "Your order could not be completed";
        String message = "Order " + payload.orderId() + " could not be completed: " + payload.reason() + ".";
        Notification notification = Notification.create(payload.orderId(), payload.userId(),
                NotificationType.ORDER_FAILED, subject, message);
        notificationRepository.save(notification);
        notificationSender.send(notification, envelope.correlationId());
    }

    private boolean firstDelivery(UUID eventId) {
        int inserted = processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId);
        if (inserted == 0) {
            log.info("Duplicate delivery of event {} - already processed, skipping", eventId);
            return false;
        }
        return true;
    }
}
