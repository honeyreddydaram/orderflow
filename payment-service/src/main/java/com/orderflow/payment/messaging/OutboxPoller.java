package com.orderflow.payment.messaging;

import com.orderflow.payment.domain.OutboxEvent;
import com.orderflow.payment.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Publishes unpublished outbox rows to Kafka. Runs as its own transaction per row, independent of
 * whatever business transaction originally wrote the row - a publish failure here never rolls
 * back business state that already committed; the row simply stays unpublished and is retried on
 * the next poll. See docs/architecture.md section 12.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> outboxKafkaTemplate;

    public OutboxPoller(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> outboxKafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxKafkaTemplate = outboxKafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${orderflow.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishUnpublished() {
        List<OutboxEvent> unpublished = outboxEventRepository.findUnpublished();
        for (OutboxEvent event : unpublished) {
            try {
                outboxKafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload()).get();
                event.markPublished();
            } catch (Exception e) {
                log.warn("Failed to publish outbox event {} ({}) to {} - will retry next poll",
                        event.getId(), event.getEventType(), event.getTopic(), e);
            }
        }
    }
}
