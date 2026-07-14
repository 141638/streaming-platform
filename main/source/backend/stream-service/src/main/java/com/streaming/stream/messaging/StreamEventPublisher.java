package com.streaming.stream.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.common.messaging.StreamEvent;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive wrapper around {@link KafkaTemplate} for publishing stream lifecycle events.
 *
 * <p>All publish methods are non-blocking — they bridge {@link java.util.concurrent.CompletableFuture}
 * into the reactive chain via {@link Mono#fromFuture} and offload to
 * {@link Schedulers#boundedElastic()} so the Netty event loop is never blocked.
 *
 * <p>Failures are logged and swallowed — the caller's reactive chain is never
 * terminated by a Kafka error (at-most-once delivery for Phase 2.0).
 */
@Service
@RequiredArgsConstructor
public class StreamEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(StreamEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${streaming.kafka.topic.stream-control:stream.control}")
    private String topic;

    /**
     * Publish a stream lifecycle event to Kafka.
     *
     * @return {@link Mono#empty()} — never errors, even if Kafka is unreachable
     */
    public Mono<Void> publish(StreamEvent event) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(event))
                .flatMap(payload ->
                        Mono.fromFuture(kafkaTemplate.send(topic, event.streamId(), payload))
                                .timeout(Duration.ofSeconds(2))
                )
                .doOnSuccess(result -> log.debug("Kafka event published: type={} streamId={}",
                        event.eventType(), event.streamId()))
                .doOnError(ex -> log.warn("Kafka publish failed: type={} streamId={} error={}",
                        event.eventType(), event.streamId(), ex.toString()))
                .onErrorComplete()
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
