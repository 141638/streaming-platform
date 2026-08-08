package com.streaming.insight.domain.model;

import java.util.Map;
import java.util.UUID;

/**
 * Aggregated stream analytics computed from engagement events.
 *
 * @param streamId          the stream UUID
 * @param totalViews        total VIEW events for this stream
 * @param uniqueViewers     distinct actor_subject count
 * @param peakHour          hour of day (0-23) with the most views
 * @param category          the category at time of most recent view (nullable)
 * @param hourlyDistribution view counts grouped by hour (0-23 → count)
 */
public record StreamAnalytics(
        UUID streamId,
        long totalViews,
        long uniqueViewers,
        int peakHour,
        String category,
        Map<Integer, Long> hourlyDistribution
) {}
