package com.streaming.auth.dto.internal;

/**
 * Response for a service-account token issuance.
 */
public record IssueServiceTokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String policyVersion
) {}
