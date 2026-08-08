package com.streaming.insight.infrastructure.persistence;

import com.streaming.insight.domain.model.EngagementEventEntity;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for {@link EngagementEventEntity}.
 *
 * <p>Query methods use PostgreSQL {@code INTERVAL} syntax for recency-windowed
 * aggregation. All queries filter on {@code event_type = 'VIEW'} (Phase A only;
 * LIKE and SUBSCRIBE are Phase B).
 *
 * <p>Projection records are defined as nested records for type-safe result mapping.
 */
public interface EngagementEventRepository
        extends ReactiveCrudRepository<EngagementEventEntity, UUID> {

    /**
     * Top-N channels by view count within the given recency window.
     * Used for cold-start trending channel suggestions.
     */
    @Query("""
            SELECT target_id, COUNT(*) as view_count
            FROM insight.engagement_event
            WHERE target_type = 'CHANNEL' AND event_type = 'VIEW'
              AND occurred_at > CURRENT_TIMESTAMP - (:h || ' hours')::INTERVAL
            GROUP BY target_id ORDER BY view_count DESC LIMIT :limit""")
    Flux<ChannelViewCount> findTrendingChannels(int h, int limit);

    /**
     * Top-N categories by view count within the given recency window.
     * Null categories are excluded. Used for cold-start trending category suggestions.
     */
    @Query("""
            SELECT category, COUNT(*) as view_count
            FROM insight.engagement_event
            WHERE category IS NOT NULL AND event_type = 'VIEW'
              AND occurred_at > CURRENT_TIMESTAMP - (:h || ' hours')::INTERVAL
            GROUP BY category ORDER BY view_count DESC LIMIT :limit""")
    Flux<CategoryViewCount> findTrendingCategories(int h, int limit);

    /**
     * Aggregate view stats for a single stream: total views + unique viewers.
     */
    @Query("""
            SELECT COUNT(*) as total_views, COUNT(DISTINCT actor_subject) as unique_viewers
            FROM insight.engagement_event
            WHERE stream_id = :streamId AND event_type = 'VIEW'""")
    Mono<StreamViewStats> findStreamViewStats(UUID streamId);

    /**
     * Hourly view distribution for a single stream.
     * Rows are ordered by hour (0-23).
     */
    @Query("""
            SELECT EXTRACT(HOUR FROM occurred_at) as hour, COUNT(*) as view_count
            FROM insight.engagement_event
            WHERE stream_id = :streamId AND event_type = 'VIEW'
            GROUP BY EXTRACT(HOUR FROM occurred_at) ORDER BY hour""")
    Flux<HourlyBucket> findHourlyDistribution(UUID streamId);

    // ── Projection records ──────────────────────────────────────────────

    /** Projection for {@link #findTrendingChannels(int, int)}. */
    record ChannelViewCount(String targetId, Long viewCount) {}

    /** Projection for {@link #findTrendingCategories(int, int)}. */
    record CategoryViewCount(String category, Long viewCount) {}

    /** Projection for {@link #findStreamViewStats(UUID)}. */
    record StreamViewStats(Long totalViews, Long uniqueViewers) {}

    /** Projection for {@link #findHourlyDistribution(UUID)}. */
    record HourlyBucket(Integer hour, Long viewCount) {}
}
