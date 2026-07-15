package com.streaming.chat.config;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Kafka consumer configuration with dead-letter queue support.
 *
 * <p>After 3 retries (with 1-second fixed backoff), poison-pill messages
 * are forwarded to the {@code stream.control.dlq} dead-letter topic for
 * manual inspection and replay.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Value("${streaming.kafka.dlq-topic:stream.control.dlq}")
    private String dlqTopic;

    /**
     * Dedicated template for DLQ publishing — avoids clashing with the
     * auto-configured {@code kafkaTemplate} bean.
     */
    @Bean
    public KafkaTemplate<String, String> dlqPublisher(KafkaProperties kafkaProperties) {
        Map<String, Object> props = Map.of(
                org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafkaProperties.getBootstrapServers()),
                org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName(),
                org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()
        );
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    /**
     * Error handler with dead-letter publishing. Spring Boot auto-configuration
     * picks up any {@link CommonErrorHandler} bean and wires it into the
     * {@code ConcurrentKafkaListenerContainerFactory}.
     *
     * <p>Retry policy: 3 attempts with 1-second fixed backoff.
     * Non-retryable: serialization errors (can't succeed on retry).
     */
    @Bean
    public CommonErrorHandler commonErrorHandler(KafkaTemplate<String, String> dlqPublisher) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dlqPublisher,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(
                        dlqTopic, record.partition())
        );

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(1000L, 3L)
        );

        handler.addNotRetryableExceptions(
                SerializationException.class
        );

        log.info("Kafka DLQ error handler configured: dlqTopic={} maxRetries=3 backoff=1000ms",
                dlqTopic);
        return handler;
    }
}
