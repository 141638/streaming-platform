package com.streaming.insight.api.dto;

import com.streaming.insight.domain.model.StreamAnalytics;
import java.util.Map;
import java.util.UUID;

/**
 * REST response for {@code GET /v1/analytics/streams/{streamId}}.
 *
 * @param streamId           the stream UUID
 * @param totalViews         total VIEW events
 * @param uniqueViewers      distinct viewers
 * @param peakHour           hour of day (0-23) with the most views
 * @param category           category at time of most recent view (nullable)
 * @param hourlyDistribution view counts by hour (0 → count)
 */
public record StreamAnalyticsResponse(
        UUID streamId,
        long totalViews,
        long uniqueViewers,
        int peakHour,
        String category,
        Map<Integer, Long> hourlyDistribution
) {
    public static StreamAnalyticsResponse from(StreamAnalytics analytics) {
        return new StreamAnalyticsResponse(
                analytics.streamId(),
                analytics.totalViews(),
                analytics.uniqueViewers(),
                analytics.peakHour(),
                analytics.category(),
                analytics.hourlyDistribution()
        );
    }
}
