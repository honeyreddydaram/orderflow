package com.orderflow.order.messaging;

import com.orderflow.common.event.EventEnvelope;
import com.orderflow.order.messaging.event.PaymentCompletedPayload;
import com.orderflow.order.messaging.event.PaymentFailedPayload;
import com.orderflow.order.service.OrderService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListener {

    private final OrderService orderService;

    public PaymentEventListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = "payment.completed", containerFactory = "paymentCompletedContainerFactory")
    public void onPaymentCompleted(EventEnvelope<PaymentCompletedPayload> envelope) {
        orderService.applyPaymentCompleted(envelope);
    }

    @KafkaListener(topics = "payment.failed", containerFactory = "paymentFailedContainerFactory")
    public void onPaymentFailed(EventEnvelope<PaymentFailedPayload> envelope) {
        orderService.applyPaymentFailed(envelope);
    }
}
