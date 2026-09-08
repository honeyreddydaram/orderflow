package com.orderflow.order.messaging;

import com.orderflow.common.event.EventEnvelope;
import com.orderflow.order.messaging.event.InventoryReservationFailedPayload;
import com.orderflow.order.messaging.event.InventoryReservedPayload;
import com.orderflow.order.service.OrderService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventListener {

    private final OrderService orderService;

    public InventoryEventListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = "inventory.reserved", containerFactory = "inventoryReservedContainerFactory")
    public void onInventoryReserved(EventEnvelope<InventoryReservedPayload> envelope) {
        orderService.applyInventoryReserved(envelope);
    }

    @KafkaListener(topics = "inventory.reservation-failed", containerFactory = "inventoryReservationFailedContainerFactory")
    public void onInventoryReservationFailed(EventEnvelope<InventoryReservationFailedPayload> envelope) {
        orderService.applyInventoryReservationFailed(envelope);
    }
}
