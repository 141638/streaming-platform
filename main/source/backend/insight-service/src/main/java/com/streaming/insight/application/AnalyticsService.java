package com.streaming.insight.application;

import com.streaming.insight.domain.model.StreamAnalytics;
import com.streaming.insight.infrastructure.persistence.EngagementEventRepository;
import com.streaming.insight.infrastructure.persistence.EngagementEventRepository.HourlyBucket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Computes per-stream analytics from aggregated engagement data.
 *
 * <p>Phase A supports: total views, unique viewers, peak hour, and hourly
 * distribution. Phase B will add stream-time suggestions and retention curves.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final EngagementEventRepository repository;

    public AnalyticsService(EngagementEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Compute analytics for a single stream.
     *
     * @param streamId the stream UUID
     * @return aggregated stream analytics, or an empty Mono if no views exist
     */
    public Mono<StreamAnalytics> getStreamAnalytics(UUID streamId) {
        Mono<EngagementEventRepository.StreamViewStats> stats =
                repository.findStreamViewStats(streamId);

        Mono<Map<Integer, Long>> hourly =
                repository.findHourlyDistribution(streamId)
                        .collectMap(HourlyBucket::hour, HourlyBucket::viewCount,
                                () -> new LinkedHashMap<>());

        return Mono.zip(stats, hourly)
                .map(tuple -> {
                    EngagementEventRepository.StreamViewStats s = tuple.getT1();
                    Map<Integer, Long> dist = tuple.getT2();

                    int peakHour = dist.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey)
                            .orElse(0);

                    return new StreamAnalytics(
                            streamId,
                            s.totalViews() != null ? s.totalViews() : 0L,
                            s.uniqueViewers() != null ? s.uniqueViewers() : 0L,
                            peakHour,
                            null, // category resolved in Phase B from the stream itself
                            dist
                    );
                })
                .doOnSuccess(a -> log.debug("Stream analytics computed: streamId={} views={}",
                        streamId, a.totalViews()));
    }
}
