package com.streaming.stream.service;

import com.streaming.stream.api.dto.StreamSseEvent;
import com.streaming.stream.sse.SseConnectionRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Periodically pushes viewer counts to SSE-connected viewers.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Get the set of stream IDs that have active SSE viewers from
 *       {@link SseConnectionRegistry#getActiveStreamIds()}.</li>
 *   <li>If no streams have viewers, skip the cycle — no work to do.</li>
 *   <li>For each active stream, SCAN Redis for {@code stream:presence:{id}:*}
 *       keys and count them.</li>
 *   <li>Push a {@code stream:viewers} SSE event with the count (0 if no
 *       presence keys found) to all viewers of that stream.</li>
 * </ol>
 *
 * <p>This replaces the 10-second REST polling cycle on the frontend
 * with a single server-side push. The frontend {@code PresenceService}
 * still polls as a fallback for users without an SSE connection.
 *
 * <h3>Performance</h3>
 * <p>SCAN only runs for streams with connected SSE viewers — the set is
 * typically small (tens to low hundreds). At 1,000 concurrent streams with
 * SSE viewers, ~1,000 SCAN + COUNT operations per 10s cycle, each trivial.
 */
@Service
public class ViewerCountPushService {

    private static final Logger log = LoggerFactory.getLogger(ViewerCountPushService.class);

    private static final String PRESENCE_KEY_PREFIX = "stream:presence:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final SseConnectionRegistry sseRegistry;

    public ViewerCountPushService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            SseConnectionRegistry sseRegistry) {
        this.redisTemplate = redisTemplate;
        this.sseRegistry = sseRegistry;
    }

    @Scheduled(fixedRate = 10_000)
    public void pushViewerCounts() {
        Set<UUID> activeStreamIds = sseRegistry.getActiveStreamIds();

        if (activeStreamIds.isEmpty()) {
            log.trace("Viewer count push skipped — no streams with SSE viewers");
            return;
        }

        log.debug("Pushing viewer counts for {} active stream(s)", activeStreamIds.size());

        // Count viewers per active stream via Redis SCAN
        countViewersForStreams(activeStreamIds)
                .doOnNext(result -> {
                    UUID streamId = result.streamId();
                    long count = result.count();
                    StreamSseEvent event = new StreamSseEvent(
                            "stream:viewers", streamId, null, count > 0 ? count : null);
                    sseRegistry.pushToStreamViewers(streamId, event);
                    log.trace("Pushed viewer count: streamId={} count={}", streamId, count);
                })
                .doOnError(ex -> log.warn(
                        "Viewer count push cycle failed: {}", ex.getMessage()))
                .onErrorComplete()
                .subscribe();
    }

    /**
     * For each active stream, SCAN Redis presence keys and count them.
     *
     * @param streamIds the set of stream IDs with connected SSE viewers
     * @return a Flux of (streamId, count) results
     */
    private Flux<StreamCount> countViewersForStreams(Set<UUID> streamIds) {
        ConcurrentMap<UUID, AtomicLong> counts = new ConcurrentHashMap<>();
        streamIds.forEach(id -> counts.put(id, new AtomicLong(0)));

        // SCAN all presence keys once, match against active streams
        ScanOptions scanOptions = ScanOptions.scanOptions()
                .match(PRESENCE_KEY_PREFIX + "*")
                .count(1000)
                .build();

        return redisTemplate.scan(scanOptions)
                .flatMap(key -> {
                    UUID streamId = extractStreamId(key);
                    if (streamId != null) {
                        AtomicLong counter = counts.get(streamId);
                        if (counter != null) {
                            counter.incrementAndGet();
                        }
                    }
                    return Mono.empty();
                })
                .thenMany(Flux.fromIterable(streamIds))
                .map(id -> new StreamCount(id, counts.get(id).get()));
    }

    /**
     * Extracts the stream UUID from a presence key.
     *
     * <p>Key format: {@code stream:presence:{streamId}:{viewerSubject}}
     * Example: {@code stream:presence:a1b2c3d4-...:user-uuid-here}
     *
     * @param key the full Redis key
     * @return the stream UUID, or null if parsing fails
     */
    static UUID extractStreamId(String key) {
        try {
            int prefixLen = PRESENCE_KEY_PREFIX.length();
            // Find the next colon after the prefix — that's the end of the stream ID
            int end = key.indexOf(':', prefixLen);
            if (end < 0) return null; // malformed key
            return UUID.fromString(key.substring(prefixLen, end));
        } catch (IllegalArgumentException e) {
            log.debug("Skipping malformed presence key: {}", key);
            return null;
        }
    }

    /** Internal holder for a (streamId, count) pair. */
    private record StreamCount(UUID streamId, long count) {}
}
