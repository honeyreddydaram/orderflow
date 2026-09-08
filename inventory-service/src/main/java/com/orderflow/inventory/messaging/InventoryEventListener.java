package com.orderflow.inventory.messaging;

import com.orderflow.common.event.EventEnvelope;
import com.orderflow.inventory.messaging.event.OrderCreatedPayload;
import com.orderflow.inventory.service.InventoryService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventListener {

    private final InventoryService inventoryService;

    public InventoryEventListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @KafkaListener(topics = "order.created", containerFactory = "orderCreatedContainerFactory")
    public void onOrderCreated(EventEnvelope<OrderCreatedPayload> envelope) {
        inventoryService.handleOrderCreated(envelope);
    }
}
