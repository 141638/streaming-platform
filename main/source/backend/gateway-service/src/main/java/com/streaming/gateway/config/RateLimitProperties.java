package com.streaming.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the gateway rate-limit filter.
 *
 * <p>Uses a sliding-window-log algorithm: each client IP gets a Redis
 * sorted set of request timestamps.  Every request prunes expired entries,
 * counts the remainder, and rejects if the count exceeds the limit.
 *
 * <p>The TTL on each Redis key is {@code windowSeconds * 2} so the key
 * survives for one full window after the last request — enough for a clean
 * transition without keeping dead keys around indefinitely.
 */
@ConfigurationProperties(prefix = "streaming.gateway.ratelimit")
public record RateLimitProperties(
        @DefaultValue("200") int limit,
        @DefaultValue("60") int windowSeconds
) {

    /** Window size in milliseconds (derived). */
    public long windowMs() {
        return windowSeconds * 1_000L;
    }

    /**
     * Redis key TTL in seconds (derived — 2× window so the key outlives
     * the window for clean transitions without accumulating stale keys).
     */
    public long ttlSeconds() {
        return windowSeconds * 2L;
    }
}
