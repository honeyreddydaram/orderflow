package com.orderflow.order.messaging;

import com.orderflow.order.domain.OutboxEvent;
import com.orderflow.order.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, String> outboxKafkaTemplate;

    private OutboxPoller poller() {
        return new OutboxPoller(outboxEventRepository, outboxKafkaTemplate);
    }

    @Test
    void publishUnpublished_marksRowPublished_onSuccessfulSend() {
        OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), "OrderCreated", "order.created", "{}", UUID.randomUUID());
        when(outboxEventRepository.findUnpublished()).thenReturn(List.of(event));

        SendResult<String, String> sendResult = new SendResult<>(null, mock(RecordMetadata.class));
        when(outboxKafkaTemplate.send(eq("order.created"), eq(event.getAggregateId().toString()), eq("{}")))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        poller().publishUnpublished();

        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void publishUnpublished_leavesRowUnpublished_whenSendFails() {
        OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), "OrderCreated", "order.created", "{}", UUID.randomUUID());
        when(outboxEventRepository.findUnpublished()).thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        when(outboxKafkaTemplate.send(any(String.class), any(String.class), any(String.class))).thenReturn(failed);

        poller().publishUnpublished();

        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void publishUnpublished_doesNothing_whenNoUnpublishedRows() {
        when(outboxEventRepository.findUnpublished()).thenReturn(List.of());

        poller().publishUnpublished();

        verify(outboxKafkaTemplate, org.mockito.Mockito.never()).send(any(String.class), any(String.class), any(String.class));
    }
}
