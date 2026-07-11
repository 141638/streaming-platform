package com.streaming.chat.security;

// PBAC-COMMON-CANDIDATE — extract to pbac-common in Phase 6.2

/**
 * Second segment of {@code {domain}:{kind}:{scope}} resource patterns.
 *
 * <p>Mirrors {@code com.streaming.auth.authorization.AuthResourceKind} (chat subset).
 * Phase 6.2: extract to shared {@code pbac-common} library.
 */
public enum AuthResourceKind {
    ROOM("room"),
    MESSAGE("message"),
    MODERATION("moderation");

    private final String segment;

    AuthResourceKind(String segment) {
        this.segment = segment;
    }

    public String segment() {
        return segment;
    }
}
