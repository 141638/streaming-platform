package com.streaming.stream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the heartbeat harvest service that snapshots concurrent
 * viewer counts from Redis into PostgreSQL.
 *
 * @param harvestIntervalMs interval between harvest cycles in milliseconds (default 30_000)
 */
@ConfigurationProperties(prefix = "streaming.heartbeat-harvest")
public record HeartbeatHarvestProperties(long harvestIntervalMs) {

    public HeartbeatHarvestProperties {
        if (harvestIntervalMs <= 0) {
            harvestIntervalMs = 30_000;
        }
    }
}
