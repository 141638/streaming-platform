package com.streaming.notification.config;

// PBAC-COMMON-CANDIDATE — extract to pbac-common in Phase 6.2

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streaming.jwt")
public record JwtProperties(
        String issuer,
        String hmacSecret
) {}
