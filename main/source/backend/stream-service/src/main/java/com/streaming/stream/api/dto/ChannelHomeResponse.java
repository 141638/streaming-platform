package com.streaming.stream.api.dto;

import java.util.List;

/**
 * Home-tab data for the channel page: the session rail and recent categories.
 *
 * <p>Sessions are capped at 15 (most recent). Categories are derived
 * from those same sessions so this endpoint never scans the full table.
 */
public record ChannelHomeResponse(
        List<StreamSummaryResponse> sessions,
        List<String> recentCategories
) {
    public static ChannelHomeResponse of(
            List<StreamSummaryResponse> sessions,
            List<String> recentCategories) {
        return new ChannelHomeResponse(sessions, recentCategories);
    }
}
