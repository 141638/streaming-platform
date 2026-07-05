package com.streaming.chat.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.streaming.chat.api.dto.MessageResponse;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.domain.Range.Bound;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Redis hot cache for recent chat messages.
 *
 * <p>Uses a {@code ZSET} keyed by epoch-millis score so the cache naturally
 * supports both "latest N" and cursor-based range queries. Messages beyond
 * the retention cap ({@value #MAX_RECENT}) are trimmed automatically on insert.
 *
 * <p><b>Design:</b> Redis is a latency buffer, not the system of record.
 * PostgreSQL is always correct; this cache can be empty or stale without
 * data loss — the service layer falls back to PG on miss.
 */
@Component
public class RedisMessageCache {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageCache.class);

    private static final String KEY_PREFIX = "chat:room:";
    private static final String RECENT_SUFFIX = ":recent";
    private static final int MAX_RECENT = 100;

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisMessageCache(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // -- write -------------------------------------------------------------

    /**
     * Add a message to the hot cache for its room and trim to the retention cap.
     *
     * @param roomKey the room's external key
     * @param message the message to cache
     * @return {@code true} if the message was cached successfully,
     *         {@code false} on serialization failure or Redis unavailability
     */
    public Mono<Boolean> addToRecent(String roomKey, MessageResponse message) {
        String key = recentKey(roomKey);
        String json = serialize(message);
        if (json == null) {
            return Mono.just(false);
        }
        double score = message.createdAt().toInstant().toEpochMilli();
        return redis.opsForZSet()
                .add(key, json, score)
                .flatMap(added -> trimToRetention(key).thenReturn(added))
                .onErrorResume(ex -> {
                    log.warn("Redis write failed for key={}, message already persisted to PG. Error: {}",
                            key, ex.getMessage());
                    return Mono.just(false);
                });
    }

    private Mono<Long> trimToRetention(String key) {
        long stop = -(MAX_RECENT + 1L); // remove elements from rank 0 down to -101 (keep last 100)
        return redis.opsForZSet()
                .removeRange(key, Range.closed(0L, stop))
                .doOnNext(removed -> {
                    if (removed > 0) {
                        log.debug("Trimmed {} old messages from cache key={}", removed, key);
                    }
                });
    }

    // -- read --------------------------------------------------------------

    /**
     * Read recent messages from the cache, ordered newest-first.
     *
     * @param roomKey the room's external key
     * @param limit   max number of messages to return
     * @return deserialized messages (empty list on cache miss or Redis failure)
     */
    public Mono<List<MessageResponse>> getRecent(String roomKey, int limit) {
        String key = recentKey(roomKey);
        return redis.opsForZSet()
                .reverseRangeByScore(key, Range.unbounded(), Limit.limit().count(limit))
                .collectList()
                .map(this::deserializeList)
                .doOnNext(list -> {
                    if (list.isEmpty()) {
                        log.debug("Cache miss for key={}", key);
                    }
                })
                .onErrorResume(ex -> {
                    log.warn("Redis read failed for key={}, falling back to PG. Error: {}", key,
                            ex.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * Read messages with scores strictly less than {@code maxScore},
     * ordered newest-first. Used for cursor-based pagination when scrolling up.
     *
     * @param roomKey  the room's external key
     * @param maxScore exclusive upper bound (epoch-millis of the oldest loaded message)
     * @param limit    max number of messages to return
     * @return deserialized messages (empty list on cache miss or Redis failure)
     */
    public Mono<List<MessageResponse>> getBefore(String roomKey, double maxScore, int limit) {
        String key = recentKey(roomKey);
        Range<Double> before = Range.of(Bound.unbounded(), Bound.exclusive(maxScore));
        return redis.opsForZSet()
                .reverseRangeByScore(key, before, Limit.limit().count(limit))
                .collectList()
                .map(this::deserializeList)
                .onErrorResume(ex -> {
                    log.warn("Redis before-query failed for key={}, maxScore={}. Error: {}",
                            key, maxScore, ex.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * Remove all cached data for a room (e.g. on room archive).
     */
    public Mono<Boolean> evictRoom(String roomKey) {
        String key = recentKey(roomKey);
        return redis.delete(key)
                .map(count -> count > 0)
                .onErrorResume(ex -> {
                    log.warn("Redis evict failed for key={}. Error: {}", key, ex.getMessage());
                    return Mono.just(false);
                });
    }

    // -- helpers -----------------------------------------------------------

    private static String recentKey(String roomKey) {
        return KEY_PREFIX + roomKey + RECENT_SUFFIX;
    }

    private String serialize(MessageResponse msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message to JSON: id={}", msg.id(), e);
            return null;
        }
    }

    private List<MessageResponse> deserializeList(List<String> jsons) {
        if (jsons == null || jsons.isEmpty()) {
            return Collections.emptyList();
        }
        return jsons.stream()
                .map(this::deserialize)
                .filter(m -> m != null)
                .toList();
    }

    private MessageResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, MessageResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached message, skipping entry", e);
            return null;
        }
    }
}
