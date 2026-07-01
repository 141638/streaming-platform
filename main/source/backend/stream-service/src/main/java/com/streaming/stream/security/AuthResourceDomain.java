package com.streaming.stream.security;

// PBAC-COMMON-CANDIDATE — extract to pbac-common in Phase 6.2

/**
 * First segment of {@code {domain}:{kind}:{scope}} resource patterns.
 *
 * <p>Mirrors {@code com.streaming.auth.authorization.AuthResourceDomain}.
 * Phase 6.2: extract to shared {@code pbac-common} library.
 */
public enum AuthResourceDomain {
    IDENTITY("identity"),
    STREAM("stream"),
    MEDIA("media"),
    CHAT("chat"),
    NOTIFICATION("notification"),
    PLATFORM("platform");

    private final String segment;

    AuthResourceDomain(String segment) {
        this.segment = segment;
    }

    public String segment() {
        return segment;
    }
}
