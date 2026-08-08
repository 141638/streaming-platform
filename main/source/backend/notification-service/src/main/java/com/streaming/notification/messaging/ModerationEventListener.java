package com.streaming.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.common.messaging.ModerationEvent;
import com.streaming.notification.application.NotificationService;
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
 * Consumes moderation events from the {@code chat.moderation} Kafka topic.
 *
 * <p>Each event is:
 * <ol>
 *   <li>Deserialized from JSON to {@link ModerationEvent}</li>
 *   <li>Deduplicated via Redis {@code SETNX} on {@code eventId} (24h TTL)</li>
 *   <li>Routed by {@code eventType} to {@link NotificationService} for persistence
 *       and SSE delivery</li>
 * </ol>
 *
 * <p>Mirrors {@link StreamControlListener} exactly — same dedup pattern, same
 * blocking timeout, same error-resilience strategy (Redis down → process anyway).
 */
@Component
public class ModerationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ModerationEventListener.class);
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final ObjectMapper objectMapper;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final NotificationService notificationService;
    private final ModerationEventCoalescer coalescer;

    @Value("${CHAT_MODERATION_TOPIC:chat.moderation}")
    private String topic;

    @Value("${spring.kafka.consumer.group-id}")
    private String consumerGroupId;

    public ModerationEventListener(ObjectMapper objectMapper,
                                   ReactiveRedisTemplate<String, String> redisTemplate,
                                   NotificationService notificationService,
                                   ModerationEventCoalescer coalescer) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.notificationService = notificationService;
        this.coalescer = coalescer;
    }

    @KafkaListener(
            topics = "${CHAT_MODERATION_TOPIC:chat.moderation}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onModerationEvent(String payload) {
        ModerationEvent event;
        try {
            event = objectMapper.readValue(payload, ModerationEvent.class);
        } catch (IOException e) {
            log.error("Failed to deserialize moderation event: payload={} error={}",
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
                    log.debug("Duplicate moderation event skipped: eventId={} type={}",
                            event.eventId(), event.eventType());
                    return Mono.empty();
                })
                .doOnError(ex -> log.warn(
                        "Redis dedup check failed for moderation eventId={} — processing anyway: {}",
                        event.eventId(), ex.getMessage()))
                .onErrorResume(ex -> handle(event))
                .subscribeOn(Schedulers.boundedElastic())
                .blockOptional(Duration.ofSeconds(10));
    }

    /**
     * Route the event by type to the appropriate handler.
     */
    private Mono<Void> handle(ModerationEvent event) {
        log.info("Processing moderation event: type={} roomKey={} subject={} eventId={}",
                event.eventType(), event.roomKey(), event.subject(), event.eventId());

        return switch (event.eventType()) {
            case "BANNED" -> onBanned(event);
            case "UNBANNED" -> onUnbanned(event);
            default -> {
                log.warn("Unknown moderation event type: {} (eventId={})",
                        event.eventType(), event.eventId());
                yield Mono.empty();
            }
        };
    }

    // ── Event handlers ─────────────────────────────────────────────────

    /**
     * BANNED: create a notification for the banned user and an alert for
     * the room owner (broadcaster). BANNED events are gated through the
     * coalescer — rapid duration re-edits within the 5s window are collapsed
     * (Layer 2 de-spam). UNBANNED events bypass coalescing (distinct action).
     */
    private Mono<Void> onBanned(ModerationEvent event) {
        return coalescer.shouldProcess(event)
                .flatMap(shouldProcess -> {
                    if (Boolean.TRUE.equals(shouldProcess)) {
                        log.info("BANNED: roomKey={} subject={} bannedBy={}",
                                event.roomKey(), event.subject(), event.bannedByUsername());
                        return Mono.when(
                                notificationService.createModerationNotification(event),
                                notificationService.createModeratorAlert(event)
                        );
                    }
                    log.debug("BANNED coalesced (rapid re-assert): roomKey={} subject={}",
                            event.roomKey(), event.subject());
                    return Mono.empty();
                });
    }

    /**
     * UNBANNED: create a notification for the (formerly) banned user and
     * an alert for the room owner.
     */
    private Mono<Void> onUnbanned(ModerationEvent event) {
        log.info("UNBANNED: roomKey={} subject={} bannedBy={}",
                event.roomKey(), event.subject(), event.bannedByUsername());

        return Mono.when(
                notificationService.createModerationNotification(event),
                notificationService.createModeratorAlert(event)
        );
    }
}
