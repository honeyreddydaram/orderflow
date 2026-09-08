package com.orderflow.notification.messaging;

import com.orderflow.common.event.EventEnvelope;
import com.orderflow.notification.messaging.event.OrderConfirmedPayload;
import com.orderflow.notification.messaging.event.OrderFailedPayload;
import com.orderflow.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderTerminalEventListener {

    private final NotificationService notificationService;

    public OrderTerminalEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "order.confirmed", containerFactory = "orderConfirmedContainerFactory")
    public void onOrderConfirmed(EventEnvelope<OrderConfirmedPayload> envelope) {
        notificationService.handleOrderConfirmed(envelope);
    }

    @KafkaListener(topics = "order.failed", containerFactory = "orderFailedContainerFactory")
    public void onOrderFailed(EventEnvelope<OrderFailedPayload> envelope) {
        notificationService.handleOrderFailed(envelope);
    }
}
