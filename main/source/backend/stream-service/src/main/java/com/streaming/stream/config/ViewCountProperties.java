package com.streaming.stream.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Redis-backed view-counting layer.
 *
 * @param viewTtl per-key TTL applied to each {@code stream:view:{streamId}:{viewerId}}
 *                hash. A viewer re-visiting within this window updates their
 *                {@code last_seen_at} but does not create a new unique view.
 *                After the TTL expires, the same viewer counts as a new unique.
 *                Defaults to 24h.
 */
@ConfigurationProperties(prefix = "streaming.view-count")
public record ViewCountProperties(Duration viewTtl) {

    public ViewCountProperties {
        if (viewTtl == null) {
            viewTtl = Duration.ofHours(24);
        }
    }
}
