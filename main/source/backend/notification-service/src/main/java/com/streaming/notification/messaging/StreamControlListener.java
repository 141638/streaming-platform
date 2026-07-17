package com.streaming.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.common.messaging.StreamEvent;
import com.streaming.notification.application.NotificationDispatcher;
import com.streaming.notification.application.NotificationService;
import com.streaming.notification.application.SubscriptionService;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Consumes stream lifecycle events from the {@code stream.control} Kafka topic.
 *
 * <p>Each event is:
 * <ol>
 *   <li>Deserialized from JSON to {@link StreamEvent}</li>
 *   <li>Deduplicated via Redis {@code SETNX} on {@code eventId} (24h TTL)</li>
 *   <li>Routed by {@code eventType} to {@link NotificationService} for persistence</li>
 * </ol>
 *
 * <p>Duplicate delivery is possible (Kafka at-least-once, producer retries).
 * Consumer-side dedup via {@code SETNX} is defense-in-depth — the outbox
 * already guarantees at-least-once, but network retries can cause duplicates.
 */
@Component
public class StreamControlListener {

    private static final Logger log = LoggerFactory.getLogger(StreamControlListener.class);

    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final ObjectMapper objectMapper;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final NotificationService notificationService;
    private final SubscriptionService subscriptionService;
    private final NotificationDispatcher dispatcher;

    @Value("${STREAM_CONTROL_TOPIC:stream.control}")
    private String topic;

    @Value("${spring.kafka.consumer.group-id}")
    private String consumerGroupId;

    public StreamControlListener(ObjectMapper objectMapper,
                                 ReactiveRedisTemplate<String, String> redisTemplate,
                                 NotificationService notificationService,
                                 SubscriptionService subscriptionService,
                                 NotificationDispatcher dispatcher) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.notificationService = notificationService;
        this.subscriptionService = subscriptionService;
        this.dispatcher = dispatcher;
    }

    @KafkaListener(
            topics = "${STREAM_CONTROL_TOPIC:stream.control}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onStreamControl(String payload) {
        StreamEvent event;
        try {
            event = objectMapper.readValue(payload, StreamEvent.class);
        } catch (IOException e) {
            log.error("Failed to deserialize stream event: payload={} error={}",
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
                    log.debug("Duplicate event skipped: eventId={} type={}",
                            event.eventId(), event.eventType());
                    return Mono.empty();
                })
                .doOnError(ex -> log.warn(
                        "Redis dedup check failed for eventId={} — processing anyway: {}",
                        event.eventId(), ex.getMessage()))
                .onErrorResume(ex -> handle(event))
                .blockOptional(Duration.ofSeconds(10));
    }

    /**
     * Route the event by type to the appropriate handler.
     * New event types should be added here as downstream services come online.
     */
    private Mono<Void> handle(StreamEvent event) {
        log.info("Processing stream event: type={} streamId={} broadcaster={} eventId={}",
                event.eventType(), event.streamId(), event.broadcasterSubject(),
                event.eventId());

        return switch (event.eventType()) {
            case "STREAM_STARTED" -> onStreamStarted(event);
            case "STREAM_ENDED" -> onStreamEnded(event);
            case "STREAM_CREATED" -> onStreamCreated(event);
            case "STREAM_SCHEDULED" -> onStreamScheduled(event);
            case "STREAM_CANCELLED" -> onStreamCancelled(event);
            default -> {
                log.warn("Unknown event type: {} (eventId={})",
                        event.eventType(), event.eventId());
                yield Mono.empty();
            }
        };
    }

    // ── Event handlers ─────────────────────────────────────────────────

    /**
     * STREAM_STARTED → broadcast self-notification + fan out to followers.
     *
     * <p>Fan-out is inline for MVP (per ADR-0002 §4). When subscriber counts
     * warrant it, this switches to outbox-driven {@code FanOutJob}.
     */
    private Mono<Void> onStreamStarted(StreamEvent event) {
        log.info("STREAM_STARTED: streamId={} broadcaster={} username={}",
                event.streamId(), event.broadcasterSubject(),
                event.broadcasterUsername());

        // 1. Broadcaster self-notification
        Mono<Void> broadcasterNotification = notificationService
                .createFromStreamEvent(event);

        // 2. Fan-out to followers — query active subscribers, create
        //    follower notifications, dispatch via deliverToMany
        Mono<Void> fanOut = subscriptionService
                .getSubscribers("CHANNEL", event.broadcasterSubject())
                .flatMap(sub -> notificationService.createForFollower(
                        event, sub.getSubscriberSubject()))
                .collectList()
                .flatMap(notifications -> {
                    if (notifications.isEmpty()) {
                        log.debug("No followers to notify: broadcaster={}",
                                event.broadcasterSubject());
                        return Mono.empty();
                    }
                    log.info("Fan-out to {} followers: broadcaster={}",
                            notifications.size(), event.broadcasterSubject());
                    return dispatcher.deliverToMany(notifications, 8);
                });

        return broadcasterNotification.then(fanOut);
    }

    /**
     * STREAM_ENDED → persist a STREAM_ENDED notification for the broadcaster.
     */
    private Mono<Void> onStreamEnded(StreamEvent event) {
        log.info("STREAM_ENDED: streamId={} broadcaster={}",
                event.streamId(), event.broadcasterSubject());
        return notificationService.createFromStreamEvent(event);
    }

    /**
     * STREAM_CREATED — informational only. No user-facing notification is
     * generated; the event is logged for audit purposes.
     */
    private Mono<Void> onStreamCreated(StreamEvent event) {
        log.debug("STREAM_CREATED: streamId={}", event.streamId());
        return notificationService.createFromStreamEvent(event);
    }

    /**
     * STREAM_SCHEDULED — informational only. Schedule reminders are deferred
     * to a future phase with a batch job.
     */
    private Mono<Void> onStreamScheduled(StreamEvent event) {
        log.debug("STREAM_SCHEDULED: streamId={}", event.streamId());
        return notificationService.createFromStreamEvent(event);
    }

    /**
     * STREAM_CANCELLED — informational only. No user-facing notification is
     * generated; the event is logged for audit purposes.
     */
    private Mono<Void> onStreamCancelled(StreamEvent event) {
        log.debug("STREAM_CANCELLED: streamId={}", event.streamId());
        return notificationService.createFromStreamEvent(event);
    }
}
