package com.streaming.insight.domain.model;

/**
 * A trending category suggestion computed from engagement event aggregates.
 *
 * @param category  the category name
 * @param viewCount raw view count in the recency window
 * @param score     weighted score ({@code viewCount × viewWeight})
 */
public record CategorySuggestion(
        String category,
        long viewCount,
        double score
) {}
