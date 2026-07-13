package com.streaming.stream.api.dto;

import java.util.List;

/**
 * About-tab data for the channel page: bio, social links, and derived stats.
 *
 * <p>Stats are computed from <em>all</em> sessions for the broadcaster,
 * so this endpoint performs a full-table scan. It is only called when the
 * user explicitly navigates to the About tab.
 */
public record ChannelAboutResponse(
        String bio,
        List<SocialLink> socialLinks,
        ChannelStats stats
) {
    public static ChannelAboutResponse of(
            String bio,
            List<SocialLink> socialLinks,
            ChannelStats stats) {
        return new ChannelAboutResponse(bio, socialLinks, stats);
    }
}
