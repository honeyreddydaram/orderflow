package com.orderflow.inventory.service;

import com.orderflow.inventory.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryReservationFailureWriterTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private InventoryReservationFailureWriter failureWriter;

    @Test
    void recordFailure_publishesOutboxEvent_onFreshInsert() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        when(processedEventRepository.insertIfAbsent(any(), eq(eventId))).thenReturn(1);

        failureWriter.recordFailure(eventId, orderId, "Insufficient stock", List.of(), correlationId);

        verify(outboxWriter).write(eq(orderId), eq("InventoryReservationFailed"),
                eq("inventory.reservation-failed"), any(), eq(correlationId));
    }

    @Test
    void recordFailure_skipsPublishing_whenAlreadyRecorded() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.insertIfAbsent(any(), eq(eventId))).thenReturn(0);

        failureWriter.recordFailure(eventId, UUID.randomUUID(), "Insufficient stock", List.of(), UUID.randomUUID());

        verify(outboxWriter, never()).write(any(), any(), any(), any(), any());
    }
}
