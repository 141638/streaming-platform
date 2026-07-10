package com.streaming.stream.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Derived channel statistics computed from existing session data.
 * No new schema — everything here is calculated from {@code stream_session} rows.
 *
 * @param totalStreams        number of sessions ever created by this broadcaster
 * @param totalHoursStreamed  aggregate duration of all ended streams (LIVE streams excluded — still running)
 * @param topCategory         the category most frequently streamed, or {@code null} if no sessions
 * @param firstStreamedAt     createdAt of the oldest session, or {@code null} if no sessions
 * @param categoryBreakdown   per-category stream counts, sorted by count descending
 */
public record ChannelStats(
        int totalStreams,
        long totalHoursStreamed,
        String topCategory,
        OffsetDateTime firstStreamedAt,
        List<CategoryCount> categoryBreakdown
) {}
