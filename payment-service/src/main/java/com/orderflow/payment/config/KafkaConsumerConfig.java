package com.orderflow.payment.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.payment.messaging.event.InventoryReservedPayload;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * One consumer factory + listener container factory per event type: Jackson needs the full
 * concrete generic type (EventEnvelope&lt;X&gt;) to deserialize the payload correctly, which type
 * erasure otherwise loses. See docs/architecture.md section 5 for the retry/DLT strategy this
 * error handler implements.
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:payment-service}")
    private String groupId;

    private ObjectMapper objectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    private <T> ConsumerFactory<String, EventEnvelope<T>> consumerFactory(TypeReference<EventEnvelope<T>> typeRef) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Wrapping in ErrorHandlingDeserializer turns a malformed-message deserialization failure
        // (which would otherwise crash the consumer thread before the listener even runs) into a
        // DeserializationException the container's error handler can catch and route to the DLT.
        JsonDeserializer<EventEnvelope<T>> jsonDeserializer = new JsonDeserializer<>(typeRef, objectMapper(), false);
        ErrorHandlingDeserializer<EventEnvelope<T>> valueDeserializer = new ErrorHandlingDeserializer<>(jsonDeserializer);
        ErrorHandlingDeserializer<String> keyDeserializer = new ErrorHandlingDeserializer<>(new StringDeserializer());
        return new DefaultKafkaConsumerFactory<>(props, keyDeserializer, valueDeserializer);
    }

    private DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> deadLetterKafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate);
        var backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(500L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000L);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        // A malformed message will never succeed on retry - send it to the DLT immediately.
        handler.addNotRetryableExceptions(org.springframework.kafka.support.serializer.DeserializationException.class);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<InventoryReservedPayload>> inventoryReservedContainerFactory(
            KafkaTemplate<Object, Object> deadLetterKafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<InventoryReservedPayload>>();
        factory.setConsumerFactory(consumerFactory(new TypeReference<>() {
        }));
        factory.setCommonErrorHandler(errorHandler(deadLetterKafkaTemplate));
        return factory;
    }
}
