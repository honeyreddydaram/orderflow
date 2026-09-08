package com.orderflow.payment.service;

import com.orderflow.common.error.ResourceNotFoundException;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.payment.domain.Payment;
import com.orderflow.payment.domain.PaymentStatus;
import com.orderflow.payment.dto.PaymentResponse;
import com.orderflow.payment.messaging.event.InventoryReservedPayload;
import com.orderflow.payment.messaging.event.PaymentCompletedPayload;
import com.orderflow.payment.messaging.event.PaymentFailedPayload;
import com.orderflow.payment.repository.PaymentRepository;
import com.orderflow.payment.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private OutboxWriter outboxWriter;

    private PaymentService paymentService;

    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, processedEventRepository, outboxWriter,
                new BigDecimal("10000.00"));
    }

    private EventEnvelope<InventoryReservedPayload> envelope(BigDecimal totalAmount) {
        return new EventEnvelope<>(eventId, "InventoryReserved", correlationId, Instant.now(),
                new InventoryReservedPayload(orderId, UUID.randomUUID(), userId, totalAmount, List.of()));
    }

    @Test
    void handleInventoryReserved_approves_whenAmountUnderThreshold() {
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);

        paymentService.handleInventoryReserved(envelope(new BigDecimal("29.97")));

        verify(paymentRepository).save(argThatStatusIs(PaymentStatus.COMPLETED));
        verify(outboxWriter).write(eq(orderId), eq("PaymentCompleted"), eq("payment.completed"),
                any(PaymentCompletedPayload.class), eq(correlationId));
        verify(outboxWriter, never()).write(any(), eq("PaymentFailed"), any(), any(), any());
    }

    @Test
    void handleInventoryReserved_declines_whenAmountOverThreshold() {
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);

        paymentService.handleInventoryReserved(envelope(new BigDecimal("15000.00")));

        verify(paymentRepository).save(argThatStatusIs(PaymentStatus.FAILED));
        verify(outboxWriter).write(eq(orderId), eq("PaymentFailed"), eq("payment.failed"),
                any(PaymentFailedPayload.class), eq(correlationId));
        verify(outboxWriter, never()).write(any(), eq("PaymentCompleted"), any(), any(), any());
    }

    @Test
    void handleInventoryReserved_isNoOp_forDuplicateEvent() {
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(0);

        paymentService.handleInventoryReserved(envelope(new BigDecimal("29.97")));

        verify(paymentRepository, never()).save(any());
        verify(outboxWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void getPayment_returnsResponse_whenRequesterIsOwner() {
        Payment payment = Payment.completed(orderId, userId, new BigDecimal("29.97"), "TXN-1");
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.getPayment(orderId, userId, false);

        assertThat(response.orderId()).isEqualTo(orderId);
    }

    @Test
    void getPayment_returnsResponse_whenRequesterIsAdmin() {
        Payment payment = Payment.completed(orderId, userId, new BigDecimal("29.97"), "TXN-1");
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.getPayment(orderId, UUID.randomUUID(), true);

        assertThat(response.orderId()).isEqualTo(orderId);
    }

    @Test
    void getPayment_throwsAccessDenied_whenRequesterIsNeitherOwnerNorAdmin() {
        Payment payment = Payment.completed(orderId, userId, new BigDecimal("29.97"), "TXN-1");
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.getPayment(orderId, UUID.randomUUID(), false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getPayment_throwsNotFound_whenNoPaymentForOrder() {
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(orderId, userId, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static Payment argThatStatusIs(PaymentStatus status) {
        return org.mockito.ArgumentMatchers.argThat(p -> p != null && p.getStatus() == status);
    }
}
