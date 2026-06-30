package com.streaming.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streaming.jwt")
public record JwtProperties(
        String issuer,
        String hmacSecret
) {}
