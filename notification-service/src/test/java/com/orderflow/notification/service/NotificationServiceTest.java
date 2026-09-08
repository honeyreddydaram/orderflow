package com.orderflow.notification.service;

import com.orderflow.common.event.EventEnvelope;
import com.orderflow.notification.domain.Notification;
import com.orderflow.notification.domain.NotificationType;
import com.orderflow.notification.messaging.event.OrderConfirmedPayload;
import com.orderflow.notification.messaging.event.OrderFailedPayload;
import com.orderflow.notification.repository.NotificationRepository;
import com.orderflow.notification.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private NotificationSender notificationSender;

    @InjectMocks
    private NotificationService notificationService;

    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @Test
    void handleOrderConfirmed_createsNotificationAndSends() {
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        var envelope = new EventEnvelope<>(eventId, "OrderConfirmed", correlationId, Instant.now(),
                new OrderConfirmedPayload(orderId, userId, new BigDecimal("29.97")));

        notificationService.handleOrderConfirmed(envelope);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(captor.getValue().getOrderId()).isEqualTo(orderId);
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        verify(notificationSender).send(eq(captor.getValue()), eq(correlationId));
    }

    @Test
    void handleOrderFailed_createsNotificationAndSends() {
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        var envelope = new EventEnvelope<>(eventId, "OrderFailed", correlationId, Instant.now(),
                new OrderFailedPayload(orderId, userId, "Card declined"));

        notificationService.handleOrderFailed(envelope);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.ORDER_FAILED);
        assertThat(captor.getValue().getMessage()).contains("Card declined");
        verify(notificationSender).send(eq(captor.getValue()), eq(correlationId));
    }

    @Test
    void handleOrderConfirmed_isNoOp_forDuplicateEvent() {
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(0);
        var envelope = new EventEnvelope<>(eventId, "OrderConfirmed", correlationId, Instant.now(),
                new OrderConfirmedPayload(orderId, userId, new BigDecimal("29.97")));

        notificationService.handleOrderConfirmed(envelope);

        verify(notificationRepository, never()).save(any());
        verify(notificationSender, never()).send(any(), any());
    }

    @Test
    void handleOrderFailed_isNoOp_forDuplicateEvent() {
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(0);
        var envelope = new EventEnvelope<>(eventId, "OrderFailed", correlationId, Instant.now(),
                new OrderFailedPayload(orderId, userId, "Card declined"));

        notificationService.handleOrderFailed(envelope);

        verify(notificationRepository, never()).save(any());
        verify(notificationSender, never()).send(any(), any());
    }

    @Test
    void listForUser_delegatesToRepository() {
        Notification notification = Notification.create(orderId, userId, NotificationType.ORDER_CONFIRMED,
                "Your order has been confirmed", "Order " + orderId + " has been confirmed. Total: 29.97.");
        Page<Notification> page = new PageImpl<>(List.of(notification));
        when(notificationRepository.findAllByUserId(eq(userId), any())).thenReturn(page);

        var result = notificationService.listForUser(userId, PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).orderId()).isEqualTo(orderId);
    }
}
