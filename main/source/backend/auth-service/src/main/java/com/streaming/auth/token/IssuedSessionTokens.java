package com.streaming.auth.token;

/** Access JWT + opaque rotating refresh credential returned at login / refresh. */
public record IssuedSessionTokens(
        IssuedAccessToken accessToken,
        String refreshToken,
        long refreshExpiresInSeconds
) {
}
