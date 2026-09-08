package com.orderflow.payment.messaging;

import com.orderflow.common.event.EventEnvelope;
import com.orderflow.payment.messaging.event.InventoryReservedPayload;
import com.orderflow.payment.service.PaymentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryReservedEventListener {

    private final PaymentService paymentService;

    public InventoryReservedEventListener(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @KafkaListener(topics = "inventory.reserved", containerFactory = "inventoryReservedContainerFactory")
    public void onInventoryReserved(EventEnvelope<InventoryReservedPayload> envelope) {
        paymentService.handleInventoryReserved(envelope);
    }
}
