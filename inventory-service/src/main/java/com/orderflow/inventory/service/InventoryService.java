package com.orderflow.inventory.service;

import com.orderflow.common.error.BusinessRuleException;
import com.orderflow.common.error.ResourceNotFoundException;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.inventory.domain.Reservation;
import com.orderflow.inventory.domain.ReservationItem;
import com.orderflow.inventory.domain.ReservationStatus;
import com.orderflow.inventory.domain.StockItem;
import com.orderflow.inventory.dto.CreateStockRequest;
import com.orderflow.inventory.dto.StockResponse;
import com.orderflow.inventory.exception.StockAlreadyExistsException;
import com.orderflow.inventory.messaging.event.OrderCreatedPayload;
import com.orderflow.inventory.messaging.event.PaymentCompletedPayload;
import com.orderflow.inventory.messaging.event.PaymentFailedPayload;
import com.orderflow.inventory.repository.ProcessedEventRepository;
import com.orderflow.inventory.repository.ReservationRepository;
import com.orderflow.inventory.repository.StockItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final StockItemRepository stockItemRepository;
    private final ReservationRepository reservationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final StockMutator stockMutator;
    private final StockReservationWriter stockReservationWriter;
    private final InventoryReservationFailureWriter failureWriter;

    public InventoryService(StockItemRepository stockItemRepository,
                             ReservationRepository reservationRepository,
                             ProcessedEventRepository processedEventRepository,
                             StockMutator stockMutator,
                             StockReservationWriter stockReservationWriter,
                             InventoryReservationFailureWriter failureWriter) {
        this.stockItemRepository = stockItemRepository;
        this.reservationRepository = reservationRepository;
        this.processedEventRepository = processedEventRepository;
        this.stockMutator = stockMutator;
        this.stockReservationWriter = stockReservationWriter;
        this.failureWriter = failureWriter;
    }

    // ---- REST-facing -----------------------------------------------------------------------

    @Transactional
    public StockResponse createStock(CreateStockRequest request) {
        if (stockItemRepository.existsById(request.productId())) {
            throw new StockAlreadyExistsException("Stock already exists for product " + request.productId());
        }
        StockItem item = stockItemRepository.save(StockItem.create(request.productId(), request.availableQty()));
        return StockResponse.from(item);
    }

    @Transactional(readOnly = true)
    public StockResponse getStock(UUID productId) {
        return StockResponse.from(findStockOrThrow(productId));
    }

    @Transactional
    public StockResponse adjustStock(UUID productId, int delta) {
        findStockOrThrow(productId);
        StockMutator.Result result = stockMutator.adjust(productId, delta);
        if (result != StockMutator.Result.SUCCESS) {
            throw new BusinessRuleException(
                    "Adjustment of " + delta + " would drive available stock negative for product " + productId);
        }
        return StockResponse.from(findStockOrThrow(productId));
    }

    private StockItem findStockOrThrow(UUID productId) {
        return stockItemRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("No stock record for product " + productId));
    }

    // ---- Saga event handlers ----------------------------------------------------------------

    /**
     * Not transactional itself - orchestrates two separate transactional beans. See
     * StockReservationWriter's javadoc for why the idempotency check lives inside each of those
     * transactions rather than here.
     */
    public void handleOrderCreated(EventEnvelope<OrderCreatedPayload> envelope) {
        StockReservationWriter.Outcome outcome = stockReservationWriter.attemptReservation(
                envelope.eventId(), envelope.payload().orderId(), envelope.payload().userId(),
                envelope.payload().totalAmount(), envelope.payload().items(), envelope.correlationId());

        if (outcome instanceof StockReservationWriter.Failed failed) {
            failureWriter.recordFailure(envelope.eventId(), envelope.payload().orderId(),
                    failed.reason(), failed.shortfalls(), envelope.correlationId());
        }
    }

    @Transactional
    public void applyPaymentCompleted(EventEnvelope<PaymentCompletedPayload> envelope) {
        if (!firstDelivery(envelope.eventId())) {
            return;
        }
        withReservationInExpectedState(envelope.payload().orderId(), "payment.completed", reservation -> {
            for (ReservationItem item : reservation.getItems()) {
                StockMutator.Result result = stockMutator.decrementReserved(item.getProductId(), item.getQuantity());
                if (result != StockMutator.Result.SUCCESS) {
                    log.error("Failed to permanently decrement product {} for order {} (result={}) - "
                                    + "possible data inconsistency, continuing with remaining items",
                            item.getProductId(), reservation.getOrderId(), result);
                }
            }
            reservation.setStatus(ReservationStatus.DECREMENTED);
        });
    }

    @Transactional
    public void applyPaymentFailed(EventEnvelope<PaymentFailedPayload> envelope) {
        if (!firstDelivery(envelope.eventId())) {
            return;
        }
        withReservationInExpectedState(envelope.payload().orderId(), "payment.failed", reservation -> {
            for (ReservationItem item : reservation.getItems()) {
                StockMutator.Result result = stockMutator.release(item.getProductId(), item.getQuantity());
                if (result != StockMutator.Result.SUCCESS) {
                    log.error("Failed to release product {} for order {} (result={}) - "
                                    + "possible data inconsistency, continuing with remaining items",
                            item.getProductId(), reservation.getOrderId(), result);
                }
            }
            reservation.setStatus(ReservationStatus.RELEASED);
        });
    }

    private boolean firstDelivery(UUID eventId) {
        int inserted = processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId);
        if (inserted == 0) {
            log.info("Duplicate delivery of event {} - already processed, skipping", eventId);
            return false;
        }
        return true;
    }

    private void withReservationInExpectedState(UUID orderId, String eventName, java.util.function.Consumer<Reservation> action) {
        Reservation reservation = reservationRepository.findByOrderIdWithItems(orderId).orElse(null);
        if (reservation == null) {
            log.error("Received {} for order {} with no known reservation - ignoring", eventName, orderId);
            return;
        }
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            log.warn("Received {} for order {} while reservation is {} (expected RESERVED) - ignoring",
                    eventName, orderId, reservation.getStatus());
            return;
        }
        action.accept(reservation);
    }
}
