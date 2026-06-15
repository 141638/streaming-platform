package com.streaming.auth.service;

import com.streaming.auth.exception.InvalidRefreshTokenException;
import com.streaming.auth.exception.UserAccountNotFoundException;
import com.streaming.auth.persistence.entity.RefreshTokenEntity;
import com.streaming.auth.persistence.repository.RefreshTokenRepository;
import com.streaming.auth.persistence.repository.UserAccountRepository;
import com.streaming.auth.token.OpaqueTokenGenerator;
import com.streaming.auth.token.OpaqueTokenHasher;
import com.streaming.auth.token.IssuedAccessToken;
import com.streaming.auth.token.IssuedSessionTokens;
import com.streaming.auth.token.JwtIssuerProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque rotating refresh credentials (hashed at rest). Replay of a revoked token revokes its family.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final AccessTokenIssuanceService accessTokenIssuanceService;
    private final JwtIssuerProperties jwtIssuerProperties;
    private final RefreshTokenMaintenanceService refreshTokenMaintenanceService;

    @Transactional
    public IssuedSessionTokens issueNewFamilySession(UUID userId) {
        userAccountRepository
                .findByIdAndDeleteFlagFalse(userId)
                .orElseThrow(() -> new UserAccountNotFoundException(userId));
        IssuedAccessToken access = accessTokenIssuanceService.issueForUser(userId);
        UUID familyId = UUID.randomUUID();
        String plaintext = persistNewRefresh(userId, familyId);
        return new IssuedSessionTokens(access, plaintext, jwtIssuerProperties.refreshTokenTtlSeconds());
    }

    /** Exchange a valid rotating refresh credential for new access + new refresh (same {@code token_family_id}). */
    @Transactional
    public IssuedSessionTokens rotateSession(String plaintextRefreshToken) {
        if (plaintextRefreshToken == null || plaintextRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        byte[] hash = OpaqueTokenHasher.sha256Utf8(plaintextRefreshToken.trim());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RefreshTokenEntity stored =
                refreshTokenRepository.lockByTokenHash(hash).orElseThrow(InvalidRefreshTokenException::new);

        if (stored.getRevokedAt() != null) {
            refreshTokenMaintenanceService.revokeAllActiveInFamily(stored.getTokenFamilyId());
            throw new InvalidRefreshTokenException();
        }

        if (!stored.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException("Refresh token expired");
        }

        UUID userId = stored.getUserAccountId();
        if (userAccountRepository.findByIdAndDeleteFlagFalse(userId).isEmpty()) {
            throw new InvalidRefreshTokenException("Principal no longer eligible");
        }

        IssuedAccessToken access = accessTokenIssuanceService.issueForUser(userId);

        UUID familyId = stored.getTokenFamilyId();
        stored.setRevokedAt(now);
        refreshTokenRepository.save(stored);

        String nextPlaintext = persistNewRefresh(userId, familyId);

        return new IssuedSessionTokens(access, nextPlaintext, jwtIssuerProperties.refreshTokenTtlSeconds());
    }

    private String persistNewRefresh(UUID userAccountId, UUID tokenFamilyId) {
        String plaintext = OpaqueTokenGenerator.newRefreshSecret();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserAccountId(userAccountId);
        entity.setTokenHash(OpaqueTokenHasher.sha256Utf8(plaintext));
        entity.setTokenFamilyId(tokenFamilyId);
        entity.setExpiresAt(now.plusSeconds(jwtIssuerProperties.refreshTokenTtlSeconds()));
        entity.setCreatedAt(now);
        refreshTokenRepository.save(entity);
        return plaintext;
    }
}
