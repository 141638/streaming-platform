package com.streaming.notification.messaging;

import com.streaming.common.messaging.ModerationEvent;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Latest-wins coalescing for moderation events that share the same
 * {@code (roomKey, subject)} aggregate key.
 *
 * <p>Layer 2 of the three-layer de-spam design. When a moderator rapidly edits
 * a ban's duration (1h → 24h → 7d → permanent), each change emits a BANNED
 * re-assert. Without coalescing, the banned user would receive a burst of
 * notifications.
 *
 * <p>This coalescer stores the latest {@code eventId} for each
 * {@code (roomKey, subject)} key in Redis with a short TTL (5 seconds).
 * When a new event arrives:
 * <ol>
 *   <li>If no prior event for the key exists in Redis → allow through,
 *       store the eventId.</li>
 *   <li>If a prior event exists → this is a rapid re-assert. Update
 *       Redis with the NEW eventId (latest-wins). The caller receives
 *       {@code false} (skip) for the old event and processes only the
 *       final event after the window closes.</li>
 * </ol>
 *
 * <p>The coalescing window (5s) balances responsiveness against burst
 * suppression. A real moderator takes ~1-3s between duration changes;
 * 5s catches the common burst while keeping "ban → unban 10s later"
 * as two distinct notifications.
 *
 * <p>This is defense-in-depth — Layer 1 (commit-once UX, shipped) already
 * collapses the common burst to a single PATCH, and Layer 3 (semantic
 * tiering in the frontend) suppresses toasts for duration increases.
 * If Redis is down, coalescing is skipped (allow-through) rather than
 * blocking the notification pipeline.
 */
@Component
public class ModerationEventCoalescer {

    private static final Logger log = LoggerFactory.getLogger(ModerationEventCoalescer.class);
    private static final Duration COALESCE_WINDOW = Duration.ofSeconds(5);

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public ModerationEventCoalescer(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Check whether this event should be processed or coalesced.
     *
     * <p>Uses Redis {@code SET} with NX + 5s TTL as an atomic test-and-set.
     * The first event for a key acquires the lock and is allowed through.
     * Subsequent events within the 5s window fail to acquire and are
     * coalesced (the caller should skip notification creation for them).
     *
     * <p>Redis failures are caught and return {@code true} (allow-through)
     * — coalescing is best-effort.
     *
     * @param event the moderation event
     * @return {@code true} if this event should be processed (first in window),
     *         {@code false} if it should be coalesced (superseded by a newer event)
     */
    public Mono<Boolean> shouldProcess(ModerationEvent event) {
        String key = coalesceKey(event);

        return redisTemplate.opsForValue()
                .setIfAbsent(key, event.eventId(), COALESCE_WINDOW)
                .map(acquired -> {
                    if (Boolean.TRUE.equals(acquired)) {
                        log.debug("Coalescer: first event in window — allow: key={} eventId={}",
                                key, event.eventId());
                        return true;
                    }
                    // Another event already claimed this window — update
                    // the value to the latest eventId and signal "skip"
                    redisTemplate.opsForValue()
                            .set(key, event.eventId(), COALESCE_WINDOW)
                            .subscribe();
                    log.debug("Coalescer: rapid re-assert — coalesced: key={} eventId={}",
                            key, event.eventId());
                    return false;
                })
                .onErrorReturn(true); // Redis down → allow through
    }

    private static String coalesceKey(ModerationEvent event) {
        return "coalesce:chat.moderation:" + event.roomKey() + ":" + event.subject();
    }
}
