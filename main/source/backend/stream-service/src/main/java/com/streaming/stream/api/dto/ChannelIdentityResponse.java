package com.streaming.stream.api.dto;

/**
 * Lightweight channel identity for the channel page header.
 *
 * <p>Identity (username / verified / broadcasterSubject) is resolved from
 * the newest session that has a non-null {@code broadcaster_username} —
 * older backfill-gap rows may be NULL. If no sessions exist,
 * {@code username} falls back to the path variable, {@code verified} is
 * {@code null}, and {@code broadcasterSubject} is {@code null}.
 *
 * @param username           the broadcaster's display name
 * @param verified           whether the broadcaster is a verified streamer
 * @param broadcasterSubject the broadcaster's JWT {@code sub} — used by
 *                           the notification subscription API to follow a channel
 */
public record ChannelIdentityResponse(
        String username,
        Boolean verified,
        String broadcasterSubject
) {
    public static ChannelIdentityResponse of(
            String username, Boolean verified, String broadcasterSubject) {
        return new ChannelIdentityResponse(username, verified, broadcasterSubject);
    }
}
