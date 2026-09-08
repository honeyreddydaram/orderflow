package com.orderflow.inventory.service;

import com.orderflow.common.error.BusinessRuleException;
import com.orderflow.common.error.ResourceNotFoundException;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.inventory.domain.Reservation;
import com.orderflow.inventory.domain.ReservationStatus;
import com.orderflow.inventory.domain.StockItem;
import com.orderflow.inventory.dto.CreateStockRequest;
import com.orderflow.inventory.exception.StockAlreadyExistsException;
import com.orderflow.inventory.messaging.event.PaymentCompletedPayload;
import com.orderflow.inventory.messaging.event.PaymentFailedPayload;
import com.orderflow.inventory.repository.ProcessedEventRepository;
import com.orderflow.inventory.repository.ReservationRepository;
import com.orderflow.inventory.repository.StockItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private StockItemRepository stockItemRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private StockMutator stockMutator;
    @Mock
    private StockReservationWriter stockReservationWriter;
    @Mock
    private InventoryReservationFailureWriter failureWriter;

    @InjectMocks
    private InventoryService inventoryService;

    private Reservation reservationOf(UUID orderId, UUID productId, int quantity, ReservationStatus status) {
        Reservation reservation = Reservation.create(orderId);
        reservation.addItem(productId, quantity);
        reservation.setStatus(status);
        return reservation;
    }

    // ---- REST-facing -----------------------------------------------------------------------

    @Test
    void createStock_savesNewRow() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.existsById(productId)).thenReturn(false);
        when(stockItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = inventoryService.createStock(new CreateStockRequest(productId, 10));

        assertThat(response.productId()).isEqualTo(productId);
        assertThat(response.availableQty()).isEqualTo(10);
    }

    @Test
    void createStock_rejectsDuplicate() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.existsById(productId)).thenReturn(true);

        assertThatThrownBy(() -> inventoryService.createStock(new CreateStockRequest(productId, 10)))
                .isInstanceOf(StockAlreadyExistsException.class);
    }

    @Test
    void getStock_throwsNotFound_whenMissing() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.getStock(productId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void adjustStock_succeeds() {
        UUID productId = UUID.randomUUID();
        StockItem item = StockItem.create(productId, 10);
        when(stockItemRepository.findById(productId)).thenReturn(Optional.of(item));
        when(stockMutator.adjust(productId, 5)).thenReturn(StockMutator.Result.SUCCESS);

        var response = inventoryService.adjustStock(productId, 5);

        assertThat(response.productId()).isEqualTo(productId);
    }

    @Test
    void adjustStock_throwsBusinessRuleException_whenWouldGoNegative() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.findById(productId)).thenReturn(Optional.of(StockItem.create(productId, 3)));
        when(stockMutator.adjust(productId, -10)).thenReturn(StockMutator.Result.INSUFFICIENT);

        assertThatThrownBy(() -> inventoryService.adjustStock(productId, -10))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ---- saga event handlers ----------------------------------------------------------------

    @Test
    void handleOrderCreated_recordsFailure_whenReservationFails() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        var envelope = new EventEnvelope<>(eventId, "OrderCreated", correlationId, Instant.now(),
                new com.orderflow.inventory.messaging.event.OrderCreatedPayload(orderId, UUID.randomUUID(), java.util.List.of(), BigDecimal.ZERO));
        var failed = new StockReservationWriter.Failed("Insufficient stock", java.util.List.of());
        when(stockReservationWriter.attemptReservation(eventId, orderId, envelope.payload().userId(),
                envelope.payload().totalAmount(), envelope.payload().items(), correlationId))
                .thenReturn(failed);

        inventoryService.handleOrderCreated(envelope);

        verify(failureWriter).recordFailure(eventId, orderId, "Insufficient stock", failed.shortfalls(), correlationId);
    }

    @Test
    void handleOrderCreated_doesNotRecordFailure_whenReservationSucceeds() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var envelope = new EventEnvelope<>(eventId, "OrderCreated", UUID.randomUUID(), Instant.now(),
                new com.orderflow.inventory.messaging.event.OrderCreatedPayload(orderId, UUID.randomUUID(), java.util.List.of(), BigDecimal.ZERO));
        when(stockReservationWriter.attemptReservation(any(), any(), any(), any(), any(), any()))
                .thenReturn(new StockReservationWriter.Reserved(UUID.randomUUID()));

        inventoryService.handleOrderCreated(envelope);

        verify(failureWriter, never()).recordFailure(any(), any(), any(), any(), any());
    }

    @Test
    void applyPaymentCompleted_decrementsAndMarksDecremented() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Reservation reservation = reservationOf(orderId, productId, 2, ReservationStatus.RESERVED);
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(reservationRepository.findByOrderIdWithItems(orderId)).thenReturn(Optional.of(reservation));
        when(stockMutator.decrementReserved(productId, 2)).thenReturn(StockMutator.Result.SUCCESS);

        var envelope = new EventEnvelope<>(UUID.randomUUID(), "PaymentCompleted", UUID.randomUUID(), Instant.now(),
                new PaymentCompletedPayload(orderId, UUID.randomUUID(), new BigDecimal("9.99"), "txn-1"));
        inventoryService.applyPaymentCompleted(envelope);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.DECREMENTED);
    }

    @Test
    void applyPaymentCompleted_isNoOp_whenAlreadyDecremented() {
        UUID orderId = UUID.randomUUID();
        Reservation reservation = reservationOf(orderId, UUID.randomUUID(), 2, ReservationStatus.DECREMENTED);
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(reservationRepository.findByOrderIdWithItems(orderId)).thenReturn(Optional.of(reservation));

        var envelope = new EventEnvelope<>(UUID.randomUUID(), "PaymentCompleted", UUID.randomUUID(), Instant.now(),
                new PaymentCompletedPayload(orderId, UUID.randomUUID(), new BigDecimal("9.99"), "txn-1"));
        inventoryService.applyPaymentCompleted(envelope);

        verify(stockMutator, never()).decrementReserved(any(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.DECREMENTED);
    }

    @Test
    void applyPaymentCompleted_duplicateDelivery_isNoOp() {
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(0);

        var envelope = new EventEnvelope<>(UUID.randomUUID(), "PaymentCompleted", UUID.randomUUID(), Instant.now(),
                new PaymentCompletedPayload(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("9.99"), "txn-1"));
        inventoryService.applyPaymentCompleted(envelope);

        verify(reservationRepository, never()).findByOrderIdWithItems(any());
    }

    @Test
    void applyPaymentFailed_releasesAndMarksReleased() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Reservation reservation = reservationOf(orderId, productId, 2, ReservationStatus.RESERVED);
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(reservationRepository.findByOrderIdWithItems(orderId)).thenReturn(Optional.of(reservation));
        when(stockMutator.release(productId, 2)).thenReturn(StockMutator.Result.SUCCESS);

        var envelope = new EventEnvelope<>(UUID.randomUUID(), "PaymentFailed", UUID.randomUUID(), Instant.now(),
                new PaymentFailedPayload(orderId, "Card declined"));
        inventoryService.applyPaymentFailed(envelope);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void applyPaymentFailed_unknownOrder_isNoOp() {
        UUID orderId = UUID.randomUUID();
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(reservationRepository.findByOrderIdWithItems(orderId)).thenReturn(Optional.empty());

        var envelope = new EventEnvelope<>(UUID.randomUUID(), "PaymentFailed", UUID.randomUUID(), Instant.now(),
                new PaymentFailedPayload(orderId, "Card declined"));
        inventoryService.applyPaymentFailed(envelope);

        verify(stockMutator, never()).release(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
