package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.StockItem;
import com.orderflow.inventory.messaging.event.OrderCreatedPayload;
import com.orderflow.inventory.repository.ProcessedEventRepository;
import com.orderflow.inventory.repository.ReservationRepository;
import com.orderflow.inventory.repository.StockItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level: verifies the decision logic (which items get flagged, what Outcome is returned).
 * The actual atomicity guarantee - that a failed item rolls back an already-successful sibling
 * item's reservation within the same order - is a real-transaction behavior verified by
 * InventoryEventListenerIntegrationTest against a real Postgres, not something a mocked unit test
 * can observe.
 */
@ExtendWith(MockitoExtension.class)
class StockReservationWriterTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private StockItemRepository stockItemRepository;
    @Mock
    private StockMutator stockMutator;
    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private StockReservationWriter writer;

    private final UUID orderId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @Test
    void attemptReservation_returnsAlreadyProcessed_forDuplicateEvent() {
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(0);

        var outcome = writer.attemptReservation(eventId, orderId, List.of(), correlationId);

        assertThat(outcome).isInstanceOf(StockReservationWriter.AlreadyProcessed.class);
        verify(stockMutator, never()).reserve(any(), anyInt());
    }

    @Test
    void attemptReservation_succeeds_whenAllItemsReserve() {
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(stockMutator.reserve(productA, 2)).thenReturn(StockMutator.Result.SUCCESS);
        when(stockMutator.reserve(productB, 1)).thenReturn(StockMutator.Result.SUCCESS);

        var items = List.of(
                new OrderCreatedPayload.Item(productA, 2, new BigDecimal("9.99")),
                new OrderCreatedPayload.Item(productB, 1, new BigDecimal("4.99")));

        var outcome = writer.attemptReservation(eventId, orderId, items, correlationId);

        assertThat(outcome).isInstanceOf(StockReservationWriter.Reserved.class);
        verify(reservationRepository, times(1)).save(any());
        verify(outboxWriter).write(eq(orderId), eq("InventoryReserved"), eq("inventory.reserved"), any(), eq(correlationId));
    }

    @Test
    void attemptReservation_fails_whenOneOfSeveralItemsInsufficient() {
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(stockMutator.reserve(productA, 2)).thenReturn(StockMutator.Result.SUCCESS);
        when(stockMutator.reserve(productB, 100)).thenReturn(StockMutator.Result.INSUFFICIENT);
        when(stockItemRepository.findById(productB)).thenReturn(Optional.of(stockOf(productB, 3)));

        var items = List.of(
                new OrderCreatedPayload.Item(productA, 2, new BigDecimal("9.99")),
                new OrderCreatedPayload.Item(productB, 100, new BigDecimal("4.99")));

        var outcome = writer.attemptReservation(eventId, orderId, items, correlationId);

        assertThat(outcome).isInstanceOf(StockReservationWriter.Failed.class);
        var failed = (StockReservationWriter.Failed) outcome;
        assertThat(failed.shortfalls()).hasSize(1);
        assertThat(failed.shortfalls().get(0).productId()).isEqualTo(productB);
        assertThat(failed.shortfalls().get(0).requestedQty()).isEqualTo(100);
        assertThat(failed.shortfalls().get(0).availableQty()).isEqualTo(3);
        verify(reservationRepository, never()).save(any());
        verify(outboxWriter, never()).write(any(), any(), any(), any(), any());
    }

    private static StockItem stockOf(UUID productId, int available) {
        return StockItem.create(productId, available);
    }
}
