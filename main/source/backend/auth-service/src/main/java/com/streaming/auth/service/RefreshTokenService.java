package com.streaming.auth.service;

import com.streaming.auth.exception.InvalidRefreshTokenException;
import com.streaming.auth.exception.UserAccountNotFoundException;
import com.streaming.auth.infrastructure.redis.RefreshTokenRedisService;
import com.streaming.auth.infrastructure.redis.RefreshTokenRedisService.RotateResult;
import com.streaming.auth.persistence.repository.UserAccountRepository;
import com.streaming.auth.token.IssuedAccessToken;
import com.streaming.auth.token.IssuedSessionTokens;
import com.streaming.auth.token.JwtIssuerProperties;
import com.streaming.auth.token.OpaqueTokenGenerator;
import com.streaming.common.crypto.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Opaque rotating refresh credentials (hashed at rest, stored in Redis).
 *
 * <p>Token rotation is handled by an atomic Lua script — no application-level
 * locks or transactions are needed. Replay of a revoked token triggers
 * family-wide revocation inside the same atomic script.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRedisService redisService;
    private final UserAccountRepository userAccountRepository;
    private final AccessTokenIssuanceService accessTokenIssuanceService;
    private final JwtIssuerProperties jwtIssuerProperties;

    /** Issue a new access + refresh token pair for a user (login). */
    public IssuedSessionTokens issueNewFamilySession(UUID userId) {
        userAccountRepository
                .findByIdAndDeleteFlagFalse(userId)
                .orElseThrow(() -> new UserAccountNotFoundException(userId));

        IssuedAccessToken access = accessTokenIssuanceService.issueForUser(userId);
        UUID familyId = UUID.randomUUID();
        String plaintext = persistNewRefresh(userId, familyId);
        return new IssuedSessionTokens(access, plaintext, jwtIssuerProperties.refreshTokenTtlSeconds());
    }

    /**
     * Exchange a valid rotating refresh credential for a new access + refresh token pair.
     *
     * <p>The Lua script atomically validates, detects replays, revokes the old token,
     * and persists the new one — all in a single Redis round-trip.
     */
    public IssuedSessionTokens rotateSession(String plaintextRefreshToken) {
        if (plaintextRefreshToken == null || plaintextRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }

        String oldHash = HashUtils.sha256Hex(plaintextRefreshToken.trim());

        // Look up old token metadata for family/user context
        var oldMetadata = redisService.findByHash(oldHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        // Verify user still exists (non-Redis check — PostgreSQL source of truth)
        UUID userId = oldMetadata.userId();
        if (userAccountRepository.findByIdAndDeleteFlagFalse(userId).isEmpty()) {
            throw new InvalidRefreshTokenException("Principal no longer eligible");
        }

        // Issue access JWT before rotation (if this fails, no state has changed)
        IssuedAccessToken access = accessTokenIssuanceService.issueForUser(userId);

        // Generate the replacement token
        String newPlaintext = OpaqueTokenGenerator.newRefreshSecret();
        String newHash = HashUtils.sha256Hex(newPlaintext);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plusSeconds(jwtIssuerProperties.refreshTokenTtlSeconds());

        // Atomic rotation via Lua script
        RotateResult result = redisService.rotate(
                oldHash, newHash, userId, oldMetadata.familyId(), expiresAt);

        return switch (result) {
            case OK -> new IssuedSessionTokens(access, newPlaintext, jwtIssuerProperties.refreshTokenTtlSeconds());
            case EXPIRED -> throw new InvalidRefreshTokenException("Refresh token expired");
            case REVOKED_FAMILY, INVALID -> throw new InvalidRefreshTokenException();
        };
    }

    /**
     * Revoke all active tokens in the family identified by the given refresh token.
     * Idempotent — quietly succeeds if the token is not found or already revoked.
     */
    public void revokeTokensByRefreshToken(String plaintextRefreshToken) {
        if (plaintextRefreshToken == null || plaintextRefreshToken.isBlank()) {
            return;
        }
        String hash = HashUtils.sha256Hex(plaintextRefreshToken.trim());
        redisService.findByHash(hash).ifPresent(meta ->
                redisService.revokeFamily(meta.familyId()));
    }

    private String persistNewRefresh(UUID userAccountId, UUID tokenFamilyId) {
        String plaintext = OpaqueTokenGenerator.newRefreshSecret();
        String hash = HashUtils.sha256Hex(plaintext);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plusSeconds(jwtIssuerProperties.refreshTokenTtlSeconds());
        redisService.issueNewFamily(hash, userAccountId, tokenFamilyId, expiresAt);
        return plaintext;
    }
}
