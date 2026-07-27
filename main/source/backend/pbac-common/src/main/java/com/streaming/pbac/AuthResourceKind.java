package com.streaming.pbac;

/**
 * Second segment of {@code {domain}:{kind}:{scope}} resource patterns.
 *
 * <p>Mirrors {@code com.streaming.auth.authorization.AuthResourceKind}.
 */
public enum AuthResourceKind {
    USER("user"),
    CREDENTIAL("credential"),
    SESSION("session"),
    PUBLISH_KEY("publish-key"),
    ARCHIVE("archive"),
    PLAYBACK("playback"),
    ROOM("room"),
    MESSAGE("message"),
    MODERATION("moderation"),
    SUBSCRIPTION("subscription"),
    OUTBOX("outbox"),
    ADMIN("admin");

    private final String segment;

    AuthResourceKind(String segment) {
        this.segment = segment;
    }

    public String segment() {
        return segment;
    }
}
