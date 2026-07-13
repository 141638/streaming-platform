package com.streaming.stream.api.dto;

/**
 * Lightweight channel identity for the channel page header.
 *
 * <p>Identity (username / verified) is resolved from the newest session
 * that has a non-null {@code broadcaster_username} — older backfill-gap
 * rows may be NULL. If no sessions exist, {@code username} falls back to
 * the path variable and {@code verified} is {@code null}.
 */
public record ChannelIdentityResponse(
        String username,
        Boolean verified
) {
    public static ChannelIdentityResponse of(String username, Boolean verified) {
        return new ChannelIdentityResponse(username, verified);
    }
}
