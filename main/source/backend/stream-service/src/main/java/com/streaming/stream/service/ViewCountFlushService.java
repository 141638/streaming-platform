package com.streaming.stream.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Periodically flushes Redis view-event hashes to PostgreSQL.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>SCAN Redis for {@code stream:view:*} keys.</li>
 *   <li>For each key, parse the {@code streamId} and {@code viewerId}.</li>
 *   <li>HGETALL the hash fields ({@code user_id, first_seen_at, last_seen_at}).</li>
 *   <li>INSERT into {@code stream_view_event} — ON CONFLICT (stream_id, user_id)
 *       DO UPDATE {@code last_seen_at} and {@code first_seen_at}.</li>
 *   <li>UPDATE {@code stream_session.views} from the events table
 *       (denormalized unique viewer count).</li>
 *   <li>DELETE the Redis key (only after successful DB write).</li>
 * </ol>
 *
 * <p>Unlike the previous counter-based approach, this design keeps the Redis key
 * until the DB write succeeds — if the flush fails mid-way, the key remains and
 * is retried on the next cycle. No view events are lost.
 *
 * <h3>Key format</h3>
 * {@code stream:view:{streamId}:{viewerId}} where {@code streamId} is a UUID
 * (no colons) and {@code viewerId} is a JWT subject (UUID, no colons) or an
 * IP-based fallback ({@code ip:x.x.x.x}). The first colon after the prefix
 * separates the two — IPv6 addresses (which contain colons) are preserved in
 * the viewerId portion.
 *
 * @see StreamService#trackViewEvent(UUID, String)
 */
@Service
public class ViewCountFlushService {

    private static final Logger log = LoggerFactory.getLogger(ViewCountFlushService.class);

    private static final String KEY_PREFIX = "stream:view:";
    private static final int KEY_PREFIX_LEN = KEY_PREFIX.length();

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final DatabaseClient databaseClient;

    public ViewCountFlushService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            DatabaseClient databaseClient) {
        this.redisTemplate = redisTemplate;
        this.databaseClient = databaseClient;
    }

    /**
     * Flush Redis view-event hashes to PostgreSQL.
     * Runs every {@code streaming.view-count.flush-interval-ms} (default 5 min).
     */
    @Scheduled(fixedDelayString = "${streaming.view-count.flush-interval-ms:300000}")
    public void flushViewCounts() {
        log.debug("Starting view-event flush…");

        redisTemplate.scan(ScanOptions.scanOptions()
                        .match(KEY_PREFIX + "*")
                        .build())
                .flatMap(this::flushOne, 16) // concurrency = 16
                .doOnComplete(() -> log.debug("View-event flush complete."))
                .doOnError(ex -> log.error("View-event flush failed", ex))
                .onErrorComplete()
                .then()
                .blockOptional(Duration.ofSeconds(290));
    }

    /**
     * Flush a single Redis view-event hash to PostgreSQL.
     *
     * @param key the full Redis key, e.g. {@code stream:view:<uuid>:<viewerId>}
     */
    private Mono<Void> flushOne(String key) {
        // -- 1. Parse streamId + viewerId from key --------------------------------
        String payload = key.substring(KEY_PREFIX_LEN);
        int sep = payload.indexOf(':');
        if (sep < 0) {
            log.warn("Skipping malformed view-event key (no viewer separator): {}", key);
            return redisTemplate.delete(key).then();
        }
        String streamIdStr = payload.substring(0, sep);
        String viewerId = payload.substring(sep + 1);

        UUID streamId;
        try {
            streamId = UUID.fromString(streamIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping malformed view-event key (bad UUID): {}", key);
            return redisTemplate.delete(key).then();
        }

        // -- 2. Read hash fields ------------------------------------------------
        return readHash(key)
                .flatMap(fields -> {
                    if (fields.isEmpty()) {
                        log.debug("View-event hash empty or expired: {}", key);
                        return redisTemplate.delete(key).then();
                    }

                    String firstSeenRaw = fields.get("first_seen_at");
                    String lastSeenRaw = fields.get("last_seen_at");

                    Instant firstSeen = parseEpochMillis(firstSeenRaw);
                    Instant lastSeen = parseEpochMillis(lastSeenRaw);

                    if (firstSeen == null || lastSeen == null) {
                        log.warn("Skipping view-event hash with missing timestamps: key={}", key);
                        return redisTemplate.delete(key).then();
                    }

                    // -- 3. Upsert into stream_view_event -----------------------
                    return upsertEvent(streamId, viewerId, firstSeen, lastSeen)
                            // -- 4. Update denormalized views count --------------
                            .then(updateDenormalizedViews(streamId))
                            // -- 5. Delete Redis key (DB write succeeded) --------
                            .then(redisTemplate.delete(key).then())
                            .doOnSuccess(unused -> log.debug(
                                    "Flushed view event: stream={} viewer={}", streamId, viewerId));
                });
    }

    // ── Redis helpers ─────────────────────────────────────────────────────────

    /**
     * Read all fields from a Redis hash as a flat {@code Map<String, String>}.
     */
    private Mono<Map<String, String>> readHash(String key) {
        return redisTemplate.opsForHash()
                .entries(key)
                .collectMap(
                        e -> e.getKey().toString(),
                        e -> e.getValue().toString());
    }

    /**
     * Parse an epoch-millis timestamp string into an {@link Instant}.
     *
     * @return the parsed instant, or {@code null} on failure
     */
    private static Instant parseEpochMillis(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Database helpers ──────────────────────────────────────────────────────

    /**
     * INSERT a view event, or UPDATE {@code last_seen_at} / {@code first_seen_at}
     * if this (stream_id, user_id) pair already exists.
     */
    private Mono<Void> upsertEvent(UUID streamId, String viewerId,
                                   Instant firstSeen, Instant lastSeen) {
        return databaseClient.sql("""
                        INSERT INTO stream.stream_view_event
                            (stream_id, user_id, first_seen_at, last_seen_at)
                        VALUES (:streamId, :userId, :firstSeen, :lastSeen)
                        ON CONFLICT (stream_id, user_id) DO UPDATE SET
                            last_seen_at = GREATEST(
                                stream_view_event.last_seen_at,
                                EXCLUDED.last_seen_at),
                            first_seen_at = LEAST(
                                stream_view_event.first_seen_at,
                                EXCLUDED.first_seen_at)
                        """)
                .bind("streamId", streamId)
                .bind("userId", viewerId)
                .bind("firstSeen", firstSeen)
                .bind("lastSeen", lastSeen)
                .then()
                .doOnError(ex -> log.warn(
                        "Failed to upsert view event: stream={} viewer={}: {}",
                        streamId, viewerId, ex.getMessage()));
    }

    /**
     * Recompute the denormalized {@code views} column from the events table.
     * This ensures the count always equals {@code COUNT(DISTINCT user_id)}.
     */
    private Mono<Void> updateDenormalizedViews(UUID streamId) {
        return databaseClient.sql("""
                        UPDATE stream.stream_session
                        SET views = (
                            SELECT COUNT(*)
                            FROM stream.stream_view_event
                            WHERE stream_id = :streamId
                        )
                        WHERE id = :streamId
                        """)
                .bind("streamId", streamId)
                .then()
                .doOnError(ex -> log.warn(
                        "Failed to update denormalized views for stream={}: {}",
                        streamId, ex.getMessage()));
    }
}
