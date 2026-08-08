package com.streaming.insight.application;

import com.streaming.insight.config.InsightProperties;
import com.streaming.insight.domain.model.CategorySuggestion;
import com.streaming.insight.domain.model.ChannelSuggestion;
import com.streaming.insight.infrastructure.persistence.EngagementEventRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Computes cold-start suggestions from aggregated engagement data.
 *
 * <p>Phase A uses only view counts with a configurable recency window.
 * Phase B will add LIKE and SUBSCRIBE signals for personalized suggestions.
 */
@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    private final EngagementEventRepository repository;
    private final InsightProperties properties;

    public SuggestionService(EngagementEventRepository repository, InsightProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Top-N trending channels by view count within the configured recency window.
     */
    public Flux<ChannelSuggestion> findTrendingChannels() {
        int hours = recencyWindowHours();
        int limit = properties.suggestions().channelLimit();
        double weight = properties.scoring().viewWeight();

        return repository.findTrendingChannels(hours, limit)
                .map(row -> new ChannelSuggestion(
                        row.targetId(),
                        row.viewCount() != null ? row.viewCount() : 0L,
                        (row.viewCount() != null ? row.viewCount() : 0L) * weight))
                .doOnComplete(() -> log.debug("Trending channels computed: window={}h limit={}", hours, limit));
    }

    /**
     * Top-N trending categories by view count within the configured recency window.
     */
    public Flux<CategorySuggestion> findTrendingCategories() {
        int hours = recencyWindowHours();
        int limit = properties.suggestions().categoryLimit();
        double weight = properties.scoring().viewWeight();

        return repository.findTrendingCategories(hours, limit)
                .map(row -> new CategorySuggestion(
                        row.category(),
                        row.viewCount() != null ? row.viewCount() : 0L,
                        (row.viewCount() != null ? row.viewCount() : 0L) * weight))
                .doOnComplete(() -> log.debug("Trending categories computed: window={}h limit={}", hours, limit));
    }

    /**
     * Convert the ISO-8601 duration string (e.g. "P7D") to hours.
     * Simple parsing for the Phase A subset: PnD, PnW.
     * Full ISO-8601 duration parsing can be added if more complex windows are needed.
     */
    private int recencyWindowHours() {
        String window = properties.suggestions().recencyWindow();
        try {
            if (window.startsWith("P") && window.endsWith("D")) {
                int days = Integer.parseInt(window.substring(1, window.length() - 1));
                return days * 24;
            }
            if (window.startsWith("P") && window.endsWith("W")) {
                int weeks = Integer.parseInt(window.substring(1, window.length() - 1));
                return weeks * 7 * 24;
            }
            // Try java.time.Duration.parse for standard formats
            return (int) Duration.parse(window).toHours();
        } catch (Exception e) {
            log.warn("Failed to parse recency window '{}', falling back to 168h (7 days): {}",
                    window, e.getMessage());
            return 168; // default: 7 days
        }
    }
}
