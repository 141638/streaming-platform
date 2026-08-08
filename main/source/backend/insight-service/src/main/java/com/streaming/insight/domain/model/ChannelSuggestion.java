package com.streaming.insight.domain.model;

/**
 * A trending channel suggestion computed from engagement event aggregates.
 *
 * @param channelUsername the broadcaster username (target_id for CHANNEL views)
 * @param viewCount       raw view count in the recency window
 * @param score           weighted score ({@code viewCount × viewWeight})
 */
public record ChannelSuggestion(
        String channelUsername,
        long viewCount,
        double score
) {}
