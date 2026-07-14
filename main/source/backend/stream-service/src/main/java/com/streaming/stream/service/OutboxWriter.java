package com.streaming.stream.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.common.messaging.StreamEvent;
import com.streaming.stream.persistence.entity.OutboxEvent;
import com.streaming.stream.persistence.repository.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Writes an {@link OutboxEvent} to the outbox table in the same database
 * transaction as the entity change that triggered it.
 *
 * <p>Callers (e.g. {@link StreamService}) MUST invoke {@link #write} within
 * a {@code @Transactional} reactive chain so the outbox insert and the entity
 * save share a single transaction.
 *
 * <p>This replaces the fire-and-forget {@code eventPublisher.publish(event).subscribe()}
 * pattern with a durable, transactionally-guaranteed event record.
 */
@Service
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Serialize the event to JSON and insert an outbox row.
     *
     * @param event the canonical stream lifecycle event
     * @return the saved outbox entity
     */
    public Mono<OutboxEvent> write(StreamEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalStateException(
                    "Failed to serialize outbox event: type=" + event.eventType()
                    + " streamId=" + event.streamId(), e));
        }

        OutboxEvent row = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .isNew(true)
                .eventType(event.eventType())
                .streamId(UUID.fromString(event.streamId()))
                .payload(payload)
                .retryCount(0)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .published(false)
                .build();

        log.debug("Writing outbox event: type={} streamId={}", event.eventType(), event.streamId());
        return outboxRepository.save(row)
                .doOnSuccess(saved -> log.debug("Outbox event saved: id={} type={}",
                        saved.getId(), saved.getEventType()));
    }
}
