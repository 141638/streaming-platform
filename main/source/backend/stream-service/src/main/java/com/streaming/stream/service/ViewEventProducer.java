package com.streaming.stream.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.common.messaging.EngagementEvent;
import com.streaming.stream.persistence.entity.StreamSessionEntity;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Fire-and-forget Kafka producer for viewer engagement telemetry.
 *
 * <p>Unlike the outbox pattern used for {@code StreamEvent} lifecycle transitions,
 * view events are telemetry — a missed event loses one data point; a blocked
 * REST call loses a user action. The REST response returns before Kafka acks.
 *
 * <p>Serializes {@link EngagementEvent} to JSON and sends to the
 * {@code stream.view} topic on a boundedElastic thread so the caller's
 * reactive chain is never blocked.
 */
@Service
public class ViewEventProducer {

    private static final Logger log = LoggerFactory.getLogger(ViewEventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${streaming.kafka.topic.stream-view:stream.view}")
    private String topic;

    public ViewEventProducer(KafkaTemplate<String, String> kafkaTemplate,
                             ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Emit a VIEW engagement event for the given stream and viewer.
     *
     * <p>Fire-and-forget: serializes on the calling thread, sends on
     * boundedElastic. Logs and drops on failure — views are telemetry,
     * not transactions.
     *
     * @param streamId UUID of the viewed stream
     * @param viewerSubject JWT sub of the viewer
     * @param entity the stream session (provides broadcaster username + category)
     */
    public void sendViewEvent(UUID streamId, String viewerSubject,
                              StreamSessionEntity entity) {
        String targetId = entity.getBroadcasterUsername() != null
                ? entity.getBroadcasterUsername() : entity.getBroadcasterSubject();
        EngagementEvent event = EngagementEvent.viewed(
                streamId, viewerSubject, targetId, entity.getCategory());

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize view event: streamId={} viewer={}: {}",
                    streamId, viewerSubject, e.getMessage());
            return;
        }

        Mono.fromFuture(kafkaTemplate.send(topic, event.streamId(), payload))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        result -> log.trace("View event sent: eventId={}", event.eventId()),
                        error -> log.warn("Failed to send view event: streamId={}: {}",
                                streamId, error.getMessage()));
    }
}
