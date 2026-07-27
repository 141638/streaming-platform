package com.streaming.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the gateway idempotency filter.
 * <p>
 * Cached idempotency responses are stored in Redis with this TTL.
 * The default (86,400 seconds = 24 hours) matches the Stripe idempotency
 * window and prevents unbounded Redis growth.
 */
@ConfigurationProperties(prefix = "streaming.gateway.idempotency")
public record IdempotencyProperties(
        @DefaultValue("86400") long ttlSeconds
) {
}
