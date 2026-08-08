package com.streaming.insight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the insight service scoring and suggestion engine.
 *
 * <p>Bound to the {@code insight.*} namespace in {@code application.yml}.
 * Scoring weights are applied multiplicatively: {@code score = weight × view_count}.
 * Phase A only uses {@code viewWeight}; {@code likeWeight} and {@code subscribeWeight}
 * are reserved for Phase B.
 */
@ConfigurationProperties("insight")
public record InsightProperties(
        Scoring scoring,
        Suggestions suggestions,
        String streamViewTopic
) {
    public record Scoring(double viewWeight, double likeWeight, double subscribeWeight) {}
    public record Suggestions(int channelLimit, int categoryLimit, String recencyWindow) {}
}
