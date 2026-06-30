package com.streaming.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "streaming.jwt")
public record JwtIssuerProperties(
        String issuer,
        String audience,
        @DefaultValue("900") int accessTokenTtlSeconds,
        @DefaultValue("604800") int refreshTokenTtlSeconds,
        @DefaultValue("300") int resetTokenTtlSeconds,
        String hmacSecret,
        @DefaultValue("stream-service-internal") String serviceAudience,
        @DefaultValue("3600") int serviceTokenTtlSeconds,
        @DefaultValue("true") boolean cookieHttpOnly,
        @DefaultValue("false") boolean cookieSecure,
        @DefaultValue("Lax") String cookieSameSite,
        @DefaultValue("/api/auth") String cookiePath
) {
}
