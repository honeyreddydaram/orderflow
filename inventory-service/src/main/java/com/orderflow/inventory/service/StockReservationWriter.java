package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.Reservation;
import com.orderflow.inventory.messaging.event.InventoryReservedPayload;
import com.orderflow.inventory.messaging.event.OrderCreatedPayload;
import com.orderflow.inventory.repository.ProcessedEventRepository;
import com.orderflow.inventory.repository.ReservationRepository;
import com.orderflow.inventory.repository.StockItemRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Separate bean (not a private method on InventoryService) for the same reason Order Service's
 * OrderCreationWriter is: {@code @Transactional} is only honored through the Spring AOP proxy, so
 * a self-invoked call from within the same class would silently run with no transaction.
 *
 * <p>The processed_events insert happens INSIDE this same transaction, not before it. If any
 * item can't be reserved, the whole transaction (including that insert) rolls back via
 * {@code setRollbackOnly()} - the event is genuinely un-processed again, so a crash here is
 * indistinguishable from "never received" and Kafka redelivery will retry the whole thing
 * cleanly. See docs/architecture.md section 10/12 and InventoryReservationFailureWriter, which
 * records the failure outcome (with its own fresh processed_events insert) once this has rolled
 * back.
 */
@Component
public class StockReservationWriter {

    public sealed interface Outcome permits AlreadyProcessed, Reserved, Failed {
    }

    public record AlreadyProcessed() implements Outcome {
    }

    public record Reserved(UUID reservationId) implements Outcome {
    }

    public record Failed(String reason, List<ItemShortfall> shortfalls) implements Outcome {
    }

    public record ItemShortfall(UUID productId, int requestedQty, int availableQty) {
    }

    private final ProcessedEventRepository processedEventRepository;
    private final ReservationRepository reservationRepository;
    private final StockItemRepository stockItemRepository;
    private final StockMutator stockMutator;
    private final OutboxWriter outboxWriter;

    public StockReservationWriter(ProcessedEventRepository processedEventRepository,
                                   ReservationRepository reservationRepository,
                                   StockItemRepository stockItemRepository,
                                   StockMutator stockMutator,
                                   OutboxWriter outboxWriter) {
        this.processedEventRepository = processedEventRepository;
        this.reservationRepository = reservationRepository;
        this.stockItemRepository = stockItemRepository;
        this.stockMutator = stockMutator;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public Outcome attemptReservation(UUID eventId, UUID orderId, UUID userId, BigDecimal totalAmount,
                                       List<OrderCreatedPayload.Item> items, UUID correlationId) {
        int inserted = processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId);
        if (inserted == 0) {
            return new AlreadyProcessed();
        }

        List<ItemShortfall> shortfalls = new ArrayList<>();
        for (OrderCreatedPayload.Item item : items) {
            StockMutator.Result result = stockMutator.reserve(item.productId(), item.quantity());
            if (result != StockMutator.Result.SUCCESS) {
                int currentAvailable = stockItemRepository.findById(item.productId())
                        .map(s -> s.getAvailableQty())
                        .orElse(0);
                shortfalls.add(new ItemShortfall(item.productId(), item.quantity(), currentAvailable));
            }
        }

        if (!shortfalls.isEmpty()) {
            // Rolls back every reservation applied above (and the processed_events insert) in one
            // shot - all-or-nothing across the whole order's items, no partial reservations.
            // Guarded so a unit test invoking this bean directly (no @Transactional proxy, hence
            // no real transaction) doesn't blow up on NoTransactionException - in production this
            // method is only ever entered through the proxy, where a transaction is always active.
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            return new Failed("Insufficient stock", shortfalls);
        }

        Reservation reservation = Reservation.create(orderId);
        for (OrderCreatedPayload.Item item : items) {
            reservation.addItem(item.productId(), item.quantity());
        }
        reservationRepository.save(reservation);

        List<InventoryReservedPayload.Item> payloadItems = items.stream()
                .map(i -> new InventoryReservedPayload.Item(i.productId(), i.quantity()))
                .toList();
        outboxWriter.write(orderId, "InventoryReserved", "inventory.reserved",
                new InventoryReservedPayload(orderId, reservation.getId(), userId, totalAmount, payloadItems),
                correlationId);

        return new Reserved(reservation.getId());
    }
}
