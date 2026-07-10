package com.streaming.stream.api.dto;

import java.util.List;

/**
 * Safe cross-user projection for the channel page at {@code /@username}.
 *
 * <p>Any authenticated user may read any channel. This DTO deliberately
 * <strong>excludes</strong> owner-only fields: {@code broadcasterSubject},
 * publish key, {@code rtmpUrl}, and {@code streamKeyHash}.
 *
 * <p>Identity (username / verified) is resolved from the newest session
 * that has a non-null {@code broadcaster_username} — older backfill-gap
 * rows may be NULL. Always returns 200 (possibly with an empty session
 * list) for consistency; the caller decides how to render an unknown or
 * empty channel.
 *
 * @param bio          the broadcaster's channel bio / description, or {@code null} if never set
 * @param socialLinks  social platform links from the profile, or empty list if none set
 * @param stats        derived channel statistics computed from existing sessions
 */
public record ChannelResponse(
        String username,
        Boolean verified,
        List<StreamSummaryResponse> sessions,
        List<String> recentCategories,
        String bio,
        List<SocialLink> socialLinks,
        ChannelStats stats
) {
    public static ChannelResponse of(
            String username,
            Boolean verified,
            List<StreamSummaryResponse> sessions,
            List<String> recentCategories,
            String bio,
            List<SocialLink> socialLinks,
            ChannelStats stats) {
        return new ChannelResponse(username, verified, sessions, recentCategories,
                bio, socialLinks, stats);
    }
}
