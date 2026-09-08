package com.orderflow.inventory.messaging;

import com.orderflow.common.event.EventEnvelope;
import com.orderflow.inventory.messaging.event.PaymentCompletedPayload;
import com.orderflow.inventory.messaging.event.PaymentFailedPayload;
import com.orderflow.inventory.service.InventoryService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListener {

    private final InventoryService inventoryService;

    public PaymentEventListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @KafkaListener(topics = "payment.completed", containerFactory = "paymentCompletedContainerFactory")
    public void onPaymentCompleted(EventEnvelope<PaymentCompletedPayload> envelope) {
        inventoryService.applyPaymentCompleted(envelope);
    }

    @KafkaListener(topics = "payment.failed", containerFactory = "paymentFailedContainerFactory")
    public void onPaymentFailed(EventEnvelope<PaymentFailedPayload> envelope) {
        inventoryService.applyPaymentFailed(envelope);
    }
}
