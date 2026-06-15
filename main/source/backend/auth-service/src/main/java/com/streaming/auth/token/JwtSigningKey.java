package com.streaming.auth.token;

import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class JwtSigningKey {

    @Bean
    SecretKey accessTokenSigningKey(JwtIssuerProperties jwtIssuerProperties) {
        String secret = jwtIssuerProperties.hmacSecret();
        byte[] secretBytes =
                secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "streaming.jwt.hmac-secret must decode to at least 32 UTF-8 bytes for HS256.");
        }
        return Keys.hmacShaKeyFor(secretBytes);
    }
}
