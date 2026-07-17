package com.streaming.stream.service;

import com.streaming.stream.config.HeartbeatHarvestProperties;
import com.streaming.stream.persistence.repository.StreamViewerSnapshotRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * Periodically harvests Redis presence keys and snapshots concurrent viewer
 * counts into PostgreSQL for time-series analytics.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>SCAN Redis for all {@code stream:presence:*} keys.</li>
 *   <li>Group by stream ID, count keys per stream.</li>
 *   <li>UPSERT into {@code stream_viewer_snapshot} with minute-bucket
 *       granularity using {@code GREATEST()} for peak capture.</li>
 * </ol>
 *
 * <h3>Scale</h3>
 * <p>One row per stream per minute, regardless of viewer count. At 1,000
 * active streams: {@code 1000 × 1440 = 1.44M rows/day} — manageable without
 * compaction in the short term. Daily compaction (minute → hourly → daily)
 * is added via a separate scheduled job when data volume warrants it.
 *
 * <h3>Relationship to ViewCountFlushService</h3>
 * <p>{@link ViewCountFlushService} tracks <b>unique</b> viewers (dedup via
 * HSETNX, 24h TTL). This service tracks <b>concurrent</b> viewers (count via
 * SCAN, 30s granularity). Different questions, different data.
 *
 * @see ViewCountFlushService
 * @see ViewerCountPushService
 */
@Service
public class HeartbeatHarvestService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatHarvestService.class);

    private static final String PRESENCE_KEY_PREFIX = "stream:presence:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final StreamViewerSnapshotRepository snapshotRepository;
    private final HeartbeatHarvestProperties properties;

    public HeartbeatHarvestService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            StreamViewerSnapshotRepository snapshotRepository,
            HeartbeatHarvestProperties properties) {
        this.redisTemplate = redisTemplate;
        this.snapshotRepository = snapshotRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${streaming.heartbeat-harvest.harvest-interval-ms:30000}")
    public void harvest() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime minuteBucket = now.truncatedTo(ChronoUnit.MINUTES);

        ScanOptions scanOptions = ScanOptions.scanOptions()
                .match(PRESENCE_KEY_PREFIX + "*")
                .count(1000)
                .build();

        redisTemplate.scan(scanOptions)
                .collectList()
                .flatMapMany(keys -> {
                    if (keys.isEmpty()) {
                        log.trace("Heartbeat harvest: no presence keys found");
                        return Flux.empty();
                    }

                    Map<UUID, AtomicLong> counts = new ConcurrentHashMap<>();
                    for (String key : keys) {
                        UUID streamId = ViewerCountPushService.extractStreamId(key);
                        if (streamId != null) {
                            counts.computeIfAbsent(streamId,
                                    k -> new AtomicLong(0)).incrementAndGet();
                        }
                    }

                    log.info("Heartbeat harvest: {} presence keys → {} streams",
                            keys.size(), counts.size());

                    return Flux.fromIterable(counts.entrySet())
                            .flatMap(entry ->
                                    snapshotRepository.upsert(
                                            entry.getKey(),
                                            minuteBucket,
                                            entry.getValue().get()),
                                    8); // concurrency 8 for batch writes
                })
                .doOnError(ex -> log.warn(
                        "Heartbeat harvest cycle failed: {}", ex.getMessage()))
                .onErrorComplete()
                .subscribe();
    }
}
