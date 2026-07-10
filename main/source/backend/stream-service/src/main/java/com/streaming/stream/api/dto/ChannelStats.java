package com.streaming.stream.api.dto;

/**
 * Derived channel statistics computed from existing session data.
 * No new schema — everything here is calculated from {@code stream_session} rows.
 *
 * @param totalStreams        number of sessions ever created by this broadcaster
 * @param totalHoursStreamed  aggregate duration of all ENDED streams (LIVE streams excluded — still running)
 * @param topCategory         the category most frequently streamed, or {@code null} if no sessions
 */
public record ChannelStats(
        int totalStreams,
        long totalHoursStreamed,
        String topCategory
) {}
