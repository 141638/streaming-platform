package com.streaming.chat.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.common.messaging.ModerationEvent;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishes moderation events to the {@code chat.moderation} Kafka topic.
 *
 * <p>Wraps the blocking {@link KafkaTemplate#send} call in
 * {@link Mono#fromCallable} on {@link Schedulers#boundedElastic()} so the
 * reactive chain is never blocked on the Kafka producer I/O thread.
 *
 * <p><b>Fire-and-forget:</b> event emission is best-effort — the ban row
 * in PostgreSQL is the authoritative source of truth. If the broker is
 * unreachable or serialization fails, the error is logged and the caller's
 * reactive chain continues uninterrupted (degrading gracefully to the
 * Wave-1 reactive enforcement floor).
 */
@Component
public class ModerationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ModerationEventPublisher.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public ModerationEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                    ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish a moderation event to Kafka.
     *
     * <p>The message key is {@code event.subject()} so events for the same
     * banned user are ordered within a partition. Errors during serialization
     * or send are caught and logged — the caller's chain is not interrupted.
     *
     * @param event the moderation event to publish
     * @return empty Mono that completes after the send (or after an error is swallowed)
     */
    public Mono<Void> publish(ModerationEvent event) {
        return Mono.fromCallable(() -> {
            String json;
            try {
                json = objectMapper.writeValueAsString(event);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize ModerationEvent: eventId={} type={} error={}",
                        event.eventId(), event.eventType(), e.getMessage());
                return null;
            }

            log.info("Publishing moderation event: type={} roomKey={} subject={} eventId={}",
                    event.eventType(), event.roomKey(), event.subject(), event.eventId());

            try {
                kafkaTemplate.send("chat.moderation", event.subject(), json)
                        .get(SEND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                log.debug("Moderation event sent: eventId={}", event.eventId());
            } catch (Exception e) {
                log.warn("Failed to publish moderation event (broker unreachable or timeout): "
                        + "eventId={} type={} error={}", event.eventId(), event.eventType(),
                        e.getMessage());
            }
            return null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }
}
