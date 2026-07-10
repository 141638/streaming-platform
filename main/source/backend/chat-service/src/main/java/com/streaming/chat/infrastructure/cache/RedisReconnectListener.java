package com.streaming.chat.infrastructure.cache;

import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import jakarta.annotation.PostConstruct;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Evicts stale room caches when Redis re-establishes a connection.
 *
 * <p>Implements mechanism #2 of <b>ADR-0003 (Cache Staleness on Redis Restart)</b>:
 * the "defense in depth" companion to the per-key TTL applied in
 * {@link RedisMessageCache#addToRecent}. When Redis restarts with persisted
 * (RDB/AOF) data, it serves a <em>stale</em> snapshot — missing every message that
 * was written to PostgreSQL while Redis was down. Because the keys still exist,
 * the read path sees a cache hit and never rebuilds them. Evicting all
 * {@code chat:room:*:recent} keys on reconnect forces the next reader to miss and
 * rebuild from PostgreSQL (the system of record), bounding staleness to ~0s.
 *
 * <p><b>Why delete, not backfill:</b> per ADR-0003, backfilling every room from PG
 * on reconnect risks a thundering herd. Deletion is one {@code DEL} per key and the
 * rebuild is distributed across real reader requests.
 *
 * <h2>Trigger</h2>
 * The eviction logic lives in {@link #evictAllRooms()} — a self-contained,
 * independently testable and manually-invokable operation. When the reactive
 * connection factory is Lettuce (the default), this component also registers a
 * {@link RedisConnectionStateListener} that invokes {@code evictAllRooms()} on every
 * connection re-establishment (the initial connect is skipped). Registration is
 * best-effort: if the native client cannot be obtained, the eviction path remains
 * available for manual/scheduled invocation and a warning is logged — the component
 * never fails startup.
 */
@Component
public class RedisReconnectListener {

    private static final Logger log = LoggerFactory.getLogger(RedisReconnectListener.class);

    /** Matches every room's recent-message ZSET key (see {@link RedisMessageCache}). */
    private static final String SCAN_PATTERN = "chat:room:*:recent";
    private static final long SCAN_BATCH = 256L;

    private final ReactiveStringRedisTemplate redis;
    private final ReactiveRedisConnectionFactory connectionFactory;

    /** Guards against evicting on the very first connect (nothing stale yet). */
    private final AtomicBoolean firstConnect = new AtomicBoolean(true);

    public RedisReconnectListener(ReactiveStringRedisTemplate redis,
                                  ReactiveRedisConnectionFactory connectionFactory) {
        this.redis = redis;
        this.connectionFactory = connectionFactory;
    }

    /**
     * Registers the Lettuce reconnect hook when possible. Failures here are logged
     * and swallowed — the eviction path stays usable via {@link #evictAllRooms()}.
     */
    @PostConstruct
    void registerReconnectHook() {
        if (!(connectionFactory instanceof LettuceConnectionFactory lettuce)) {
            log.info("Redis factory is {} (not Lettuce); reconnect eviction is manual via evictAllRooms()",
                    connectionFactory.getClass().getSimpleName());
            return;
        }
        try {
            Object nativeClient = lettuce.getNativeClient();
            if (nativeClient instanceof RedisClient client) {
                client.addListener(new EvictOnReconnect());
                log.info("Registered Redis reconnect listener (ADR-0003 mechanism #2): "
                        + "stale '{}' keys evicted on re-establishment", SCAN_PATTERN);
            } else {
                log.info("Native Redis client unavailable (type={}); reconnect eviction is manual via evictAllRooms()",
                        nativeClient == null ? "null" : nativeClient.getClass().getSimpleName());
            }
        } catch (Exception ex) {
            log.warn("Could not register Redis reconnect listener; eviction available only via evictAllRooms(): {}",
                    ex.getMessage());
        }
    }

    /**
     * Scan and delete every {@code chat:room:*:recent} key. Safe to call at any time;
     * resilient to Redis being unavailable (returns {@code 0} instead of erroring).
     *
     * @return the number of room keys evicted
     */
    public Mono<Long> evictAllRooms() {
        return redis.scan(ScanOptions.scanOptions().match(SCAN_PATTERN).count(SCAN_BATCH).build())
                .flatMap(redis::delete)
                .reduce(0L, Long::sum)
                .doOnNext(evicted -> {
                    if (evicted > 0) {
                        log.info("Evicted {} stale room cache key(s) after Redis reconnect (ADR-0003)", evicted);
                    } else {
                        log.debug("Reconnect eviction ran; no '{}' keys present", SCAN_PATTERN);
                    }
                })
                .onErrorResume(ex -> {
                    log.warn("Reconnect eviction failed (will retry on next reconnect): {}", ex.getMessage());
                    return Mono.just(0L);
                });
    }

    /** Fires {@link #evictAllRooms()} on each Lettuce connection after the first. */
    private final class EvictOnReconnect implements RedisConnectionStateListener {
        @Override
        public void onRedisConnected(RedisChannelHandler<?, ?> connection, SocketAddress remoteAddress) {
            if (firstConnect.getAndSet(false)) {
                log.debug("Initial Redis connection established; skipping eviction");
                return;
            }
            log.info("Redis reconnect detected ({}); evicting stale room caches", remoteAddress);
            // Offload off the Lettuce I/O thread: subscription setup (onSubscribe/
            // request) runs synchronously on the caller, so subscribing here directly
            // would occupy the I/O thread during a reconnect storm.
            evictAllRooms()
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            evicted -> { /* logged in evictAllRooms */ },
                            err -> log.warn("Reconnect eviction subscription error: {}", err.getMessage()));
        }
    }
}
