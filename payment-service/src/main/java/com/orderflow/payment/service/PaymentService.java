package com.orderflow.payment.service;

import com.orderflow.common.error.ResourceNotFoundException;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.payment.domain.Payment;
import com.orderflow.payment.dto.PaymentResponse;
import com.orderflow.payment.messaging.event.InventoryReservedPayload;
import com.orderflow.payment.messaging.event.PaymentCompletedPayload;
import com.orderflow.payment.messaging.event.PaymentFailedPayload;
import com.orderflow.payment.repository.PaymentRepository;
import com.orderflow.payment.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxWriter outboxWriter;
    private final BigDecimal declineThreshold;

    public PaymentService(PaymentRepository paymentRepository,
                           ProcessedEventRepository processedEventRepository,
                           OutboxWriter outboxWriter,
                           @Value("${orderflow.payment.decline-threshold}") BigDecimal declineThreshold) {
        this.paymentRepository = paymentRepository;
        this.processedEventRepository = processedEventRepository;
        this.outboxWriter = outboxWriter;
        this.declineThreshold = declineThreshold;
    }

    // ---- REST-facing -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID orderId, UUID requesterId, boolean isAdmin) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment record for order " + orderId));
        if (!isAdmin && !payment.getUserId().equals(requesterId)) {
            throw new AccessDeniedException("Payment for order " + orderId + " does not belong to the requester");
        }
        return PaymentResponse.from(payment);
    }

    // ---- Saga event handler ------------------------------------------------------------------

    /**
     * Single-phase, unlike Inventory Service's StockReservationWriter/InventoryReservationFailureWriter
     * pair: the approve/decline decision is made before any DB write and always yields exactly one
     * outcome, so the processed_events marker, the Payment row, and the outbox event all commit
     * together in one transaction with no need for a setRollbackOnly()-driven split. See
     * docs/architecture.md section 10/12.
     */
    @Transactional
    public void handleInventoryReserved(EventEnvelope<InventoryReservedPayload> envelope) {
        UUID eventId = envelope.eventId();
        int inserted = processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId);
        if (inserted == 0) {
            log.info("Duplicate delivery of event {} - already processed, skipping", eventId);
            return;
        }

        InventoryReservedPayload payload = envelope.payload();
        UUID orderId = payload.orderId();
        UUID userId = payload.userId();
        BigDecimal amount = payload.totalAmount();

        if (amount.compareTo(declineThreshold) > 0) {
            String reason = "Payment declined: amount " + amount + " exceeds the simulated processor's limit of "
                    + declineThreshold;
            Payment payment = Payment.failed(orderId, userId, amount, reason);
            paymentRepository.save(payment);
            outboxWriter.write(orderId, "PaymentFailed", "payment.failed",
                    new PaymentFailedPayload(orderId, reason), envelope.correlationId());
            return;
        }

        String transactionRef = "TXN-" + UUID.randomUUID();
        Payment payment = Payment.completed(orderId, userId, amount, transactionRef);
        paymentRepository.save(payment);
        outboxWriter.write(orderId, "PaymentCompleted", "payment.completed",
                new PaymentCompletedPayload(orderId, payment.getId(), amount, transactionRef),
                envelope.correlationId());
    }
}
