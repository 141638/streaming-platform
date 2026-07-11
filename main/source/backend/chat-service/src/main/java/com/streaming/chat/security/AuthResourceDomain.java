package com.streaming.chat.security;

// PBAC-COMMON-CANDIDATE — extract to pbac-common in Phase 6.2

/**
 * First segment of {@code {domain}:{kind}:{scope}} resource patterns.
 *
 * <p>Mirrors {@code com.streaming.auth.authorization.AuthResourceDomain} (chat subset).
 * Phase 6.2: extract to shared {@code pbac-common} library.
 */
public enum AuthResourceDomain {
    CHAT("chat");

    private final String segment;

    AuthResourceDomain(String segment) {
        this.segment = segment;
    }

    public String segment() {
        return segment;
    }
}
