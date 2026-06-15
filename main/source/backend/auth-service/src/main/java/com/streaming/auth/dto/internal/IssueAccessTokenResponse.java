package com.streaming.auth.dto.internal;

/** Operator / machine response for internal mint endpoint. */
public record IssueAccessTokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String policyVersion
) {

}
