package com.streaming.pbac.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT validation properties consumed by the shared
 * {@link PbacSecurityAutoConfiguration#pbacJwtDecoder} bean and by any
 * service that needs to introspect token claims.
 */
@ConfigurationProperties(prefix = "streaming.jwt")
public record JwtProperties(
        String issuer,
        String hmacSecret
) {}
