package com.streaming.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT validation properties for the gateway edge.
 * <p>
 * The HMAC secret must match {@code streaming.jwt.hmac-secret} in the auth service
 * so the gateway can verify tokens the auth service issued.
 * <p>
 * The {@code hmacSecret} has no {@code @DefaultValue} — the fallback lives in
 * {@code application.yml} (matching the auth-service convention) so there is a single
 * default path to audit, not two independent fallbacks that could diverge.
 */
@ConfigurationProperties(prefix = "streaming.gateway.jwt")
public record JwtGatewayProperties(
        @DefaultValue("https://auth.streaming.local") String issuer,
        String hmacSecret
) {
}
