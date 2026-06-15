package com.streaming.auth.service;

import com.streaming.auth.exception.InvalidPasswordResetTokenException;
import com.streaming.auth.token.JwtIssuerProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates short-lived password-reset JWTs (typ: "password-reset" in the JOSE header).
 * These tokens are never accepted as Bearer tokens -- they are single-purpose.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetTokenService {

    private final SecretKey accessTokenSigningKey;
    private final JwtIssuerProperties jwtIssuerProperties;

    /**
     * Builds and signs a password-reset JWT for the given user.
     *
     * @param userId the subject (sub) of the reset token
     * @return compact JWT string
     */
    public String issueForUser(UUID userId) {
        long nowMs = System.currentTimeMillis();
        long ttlMs = jwtIssuerProperties.resetTokenTtlSeconds() * 1000L;
        Date issuedAt = new Date(nowMs);
        Date expiresAt = new Date(nowMs + ttlMs);
        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
                .header()
                .type("password-reset")
                .and()
                .issuer(jwtIssuerProperties.issuer())
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiration(expiresAt)
                .id(jti)
                .signWith(accessTokenSigningKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Validates the JWT signature, expiration, and header type, then returns the user UUID from the
     * {@code sub} claim.
     *
     * @param compactToken the compact JWT string to validate
     * @return the userId from the {@code sub} claim
     * @throws InvalidPasswordResetTokenException if the token is expired, malformed, or has a wrong
     *                                            header type
     */
    public UUID validateAndDecode(String compactToken) {
        var jwt = parseAndVerify(compactToken);

        // Reject tokens that do not have the password-reset type header.
        String headerType = (String) jwt.getHeader().get("typ");
        if (!"password-reset".equals(headerType)) {
            throw new InvalidPasswordResetTokenException();
        }

        String subject = jwt.getPayload().getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidPasswordResetTokenException();
        }

        return UUID.fromString(subject.trim());
    }

    private Jws<Claims> parseAndVerify(String compactToken) {
        if (compactToken == null || compactToken.isBlank()) {
            throw new InvalidPasswordResetTokenException();
        }
        try {
            return Jwts.parser()
                    .verifyWith(accessTokenSigningKey)
                    .build()
                    .parseSignedClaims(compactToken.trim());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidPasswordResetTokenException();
        }
    }
}
