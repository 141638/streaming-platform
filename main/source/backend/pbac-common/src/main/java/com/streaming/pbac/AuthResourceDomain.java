package com.streaming.pbac;

/**
 * First segment of {@code {domain}:{kind}:{scope}} resource patterns.
 *
 * <p>Mirrors {@code com.streaming.auth.authorization.AuthResourceDomain}.
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
