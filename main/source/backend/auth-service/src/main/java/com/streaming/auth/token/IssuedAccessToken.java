package com.streaming.auth.token;

import com.streaming.auth.service.AccessTokenIssuanceService;

/**
 * Result of {@link AccessTokenIssuanceService#issueForUser(java.util.UUID)}: compact JWT plus metadata.
 */
public record IssuedAccessToken(
        String accessToken,
        long expiresInSeconds,
        String policyVersion
) {
}
