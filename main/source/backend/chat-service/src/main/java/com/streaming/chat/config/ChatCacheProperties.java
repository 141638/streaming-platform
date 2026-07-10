package com.streaming.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Redis hot-cache layer.
 *
 * @param roomTtl per-key TTL applied to each room's recent-message ZSET on every
 *                write. Bounds the maximum staleness window after a Redis restart
 *                with persisted (but stale) data — see ADR-0003. Defaults to 1h.
 */
@ConfigurationProperties(prefix = "chat.cache")
public record ChatCacheProperties(Duration roomTtl) {

    public ChatCacheProperties {
        if (roomTtl == null) {
            roomTtl = Duration.ofHours(1);
        }
    }
}
