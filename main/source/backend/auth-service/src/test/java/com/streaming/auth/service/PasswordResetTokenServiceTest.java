package com.streaming.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streaming.auth.exception.InvalidPasswordResetTokenException;
import com.streaming.auth.token.JwtIssuerProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PasswordResetTokenService")
class PasswordResetTokenServiceTest {

    private static final String TEST_SECRET = "a-test-secret-that-is-at-least-32-bytes-long!";
    private static final String TEST_ISSUER = "https://auth.streaming.local";
    private static final int TTL_SECONDS = 300;

    private PasswordResetTokenService tokenService;

    @BeforeEach
    void setUp() {
        JwtIssuerProperties properties =
                new JwtIssuerProperties(TEST_ISSUER, "aud", 900, 604800, TTL_SECONDS, TEST_SECRET);
        byte[] secretBytes = TEST_SECRET.getBytes(StandardCharsets.UTF_8);
        SecretKey signingKey = Keys.hmacShaKeyFor(secretBytes);
        tokenService = new PasswordResetTokenService(signingKey, properties);
    }

    @Nested
    @DisplayName("issueForUser")
    class IssueForUser {

        @Test
        @DisplayName("returns a compact JWT string")
        void returnsCompactJwt() {
            UUID userId = UUID.randomUUID();
            String token = tokenService.issueForUser(userId);
            assertThat(token).isNotBlank();
            assertThat(token.split("\\.")).hasSize(3);
        }

        @Test
        @DisplayName("token can be validated and decoded back to the same userId")
        void roundTrip() {
            UUID userId = UUID.randomUUID();
            String token = tokenService.issueForUser(userId);
            UUID decoded = tokenService.validateAndDecode(token);
            assertThat(decoded).isEqualTo(userId);
        }
    }

    @Nested
    @DisplayName("validateAndDecode")
    class ValidateAndDecode {

        @Test
        @DisplayName("returns userId for a valid token")
        void validToken_returnsUserId() {
            UUID userId = UUID.randomUUID();
            String token = tokenService.issueForUser(userId);
            UUID result = tokenService.validateAndDecode(token);
            assertThat(result).isEqualTo(userId);
        }

        @Test
        @DisplayName("throws for null token")
        void nullToken_throws() {
            assertThatThrownBy(() -> tokenService.validateAndDecode(null))
                    .isInstanceOf(InvalidPasswordResetTokenException.class);
        }

        @Test
        @DisplayName("throws for blank token")
        void blankToken_throws() {
            assertThatThrownBy(() -> tokenService.validateAndDecode("  "))
                    .isInstanceOf(InvalidPasswordResetTokenException.class);
        }

        @Test
        @DisplayName("throws for tampered token (wrong signature)")
        void tamperedToken_throws() {
            UUID userId = UUID.randomUUID();
            String token = tokenService.issueForUser(userId);
            String tampered = token + "x";
            assertThatThrownBy(() -> tokenService.validateAndDecode(tampered))
                    .isInstanceOf(InvalidPasswordResetTokenException.class);
        }

        @Test
        @DisplayName("throws for expired token")
        void expiredToken_throws() {
            // Build an already-expired token manually
            long nowMs = System.currentTimeMillis();
            byte[] secretBytes = TEST_SECRET.getBytes(StandardCharsets.UTF_8);
            SecretKey signingKey = Keys.hmacShaKeyFor(secretBytes);

            String expired = Jwts.builder()
                    .header().type("password-reset").and()
                    .issuer(TEST_ISSUER)
                    .subject(UUID.randomUUID().toString())
                    .issuedAt(new Date(nowMs - 10_000))
                    .expiration(new Date(nowMs - 5_000))
                    .id(UUID.randomUUID().toString())
                    .signWith(signingKey, Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> tokenService.validateAndDecode(expired))
                    .isInstanceOf(InvalidPasswordResetTokenException.class);
        }

        @Test
        @DisplayName("throws when header typ is not 'password-reset'")
        void wrongTyp_throws() {
            long nowMs = System.currentTimeMillis();
            byte[] secretBytes = TEST_SECRET.getBytes(StandardCharsets.UTF_8);
            SecretKey signingKey = Keys.hmacShaKeyFor(secretBytes);

            String accessToken = Jwts.builder()
                    .header().type("at+jwt").and()
                    .issuer(TEST_ISSUER)
                    .subject(UUID.randomUUID().toString())
                    .issuedAt(new Date(nowMs))
                    .expiration(new Date(nowMs + 60_000))
                    .id(UUID.randomUUID().toString())
                    .signWith(signingKey, Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> tokenService.validateAndDecode(accessToken))
                    .isInstanceOf(InvalidPasswordResetTokenException.class);
        }

        @Test
        @DisplayName("throws for blank subject in token")
        void blankSubject_throws() {
            long nowMs = System.currentTimeMillis();
            byte[] secretBytes = TEST_SECRET.getBytes(StandardCharsets.UTF_8);
            SecretKey signingKey = Keys.hmacShaKeyFor(secretBytes);

            String tokenWithBlankSub = Jwts.builder()
                    .header().type("password-reset").and()
                    .issuer(TEST_ISSUER)
                    .subject("  ")
                    .issuedAt(new Date(nowMs))
                    .expiration(new Date(nowMs + 60_000))
                    .id(UUID.randomUUID().toString())
                    .signWith(signingKey, Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> tokenService.validateAndDecode(tokenWithBlankSub))
                    .isInstanceOf(InvalidPasswordResetTokenException.class);
        }
    }
}
