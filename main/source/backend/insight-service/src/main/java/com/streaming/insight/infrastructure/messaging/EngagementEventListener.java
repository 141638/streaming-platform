package com.streaming.insight.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.common.messaging.EngagementEvent;
import com.streaming.insight.application.EngagementService;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Consumes {@code stream.view} events from Kafka, deduplicates via Redis SETNX,
 * and persists engagement data for analytics.
 *
 * <p>Mirrors {@code StreamControlListener} in notification-service exactly:
 * same dedup pattern (Redis SETNX with 24h TTL), same blocking pattern
 * ({@code blockOptional(10s)} on boundedElastic), same error-resilience
 * (fall through to processing on Redis failure).
 */
@Component
public class EngagementEventListener {

    private static final Logger log = LoggerFactory.getLogger(EngagementEventListener.class);
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final ObjectMapper objectMapper;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final EngagementService engagementService;

    @Value("${insight.stream-view-topic:stream.view}")
    private String topic;

    @Value("${spring.kafka.consumer.group-id}")
    private String consumerGroupId;

    public EngagementEventListener(ObjectMapper objectMapper,
                                   ReactiveRedisTemplate<String, String> redisTemplate,
                                   EngagementService engagementService) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.engagementService = engagementService;
    }

    @KafkaListener(
            topics = "${insight.stream-view-topic:stream.view}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onEngagementEvent(String payload) {
        EngagementEvent event;
        try {
            event = objectMapper.readValue(payload, EngagementEvent.class);
        } catch (IOException e) {
            log.error("Failed to deserialize engagement event: payload={} error={}",
                    payload, e.getMessage());
            return;
        }

        String dedupKey = "dedup:" + topic + ":" + consumerGroupId + ":" + event.eventId();
        redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", DEDUP_TTL)
                .flatMap(acquired -> {
                    if (Boolean.TRUE.equals(acquired)) {
                        return handle(event);
                    }
                    log.debug("Duplicate engagement event skipped: eventId={} type={}",
                            event.eventId(), event.eventType());
                    return Mono.empty();
                })
                .doOnError(ex -> log.warn(
                        "Redis dedup check failed for eventId={} — processing anyway: {}",
                        event.eventId(), ex.getMessage()))
                .onErrorResume(ex -> handle(event))
                .subscribeOn(Schedulers.boundedElastic())
                .blockOptional(Duration.ofSeconds(10));
    }

    /**
     * Route the event by type to the appropriate handler.
     * New event types (LIKE, SUBSCRIBE) are added in Phase B.
     */
    private Mono<Void> handle(EngagementEvent event) {
        log.debug("Processing engagement event: type={} streamId={} actor={} eventId={}",
                event.eventType(), event.streamId(), event.actorSubject(),
                event.eventId());

        return switch (event.eventType()) {
            case "VIEW" -> engagementService.persistView(event);
            default -> {
                log.warn("Unknown engagement event type: {} (eventId={})",
                        event.eventType(), event.eventId());
                yield Mono.empty();
            }
        };
    }
}
