package com.streaming.insight.api;

import com.streaming.insight.api.dto.StreamAnalyticsResponse;
import com.streaming.insight.application.AnalyticsService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for per-stream analytics.
 *
 * <p>Provides post-stream dashboard data: view counts, unique viewers,
 * peak hour, and hourly distribution. Used by the streamer analytics
 * page in the Angular SPA.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    /**
     * Get analytics for a single stream.
     *
     * <p>Response 200:
     * <pre>
     * {
     *   "streamId": "550e8400-...",
     *   "totalViews": 150,
     *   "uniqueViewers": 89,
     *   "peakHour": 20,
     *   "category": "gaming",
     *   "hourlyDistribution": { "14": 25, "15": 30, "20": 45 }
     * }
     * </pre>
     */
    @GetMapping("/analytics/streams/{streamId}")
    public Mono<ResponseEntity<StreamAnalyticsResponse>> getStreamAnalytics(
            @PathVariable UUID streamId) {
        return analyticsService.getStreamAnalytics(streamId)
                .map(StreamAnalyticsResponse::from)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
