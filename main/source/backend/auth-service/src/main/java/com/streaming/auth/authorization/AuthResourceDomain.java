package com.streaming.auth.authorization;

/**
 * First segment of {@code {domain}:{kind}:{scope}} resource patterns.
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
