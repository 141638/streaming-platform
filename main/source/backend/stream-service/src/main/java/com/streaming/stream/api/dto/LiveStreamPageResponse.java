package com.streaming.stream.api.dto;

import java.util.List;

/**
 * Cursor-paginated live stream response.
 *
 * @param streams     the live streams for this page
 * @param nextCursor  {@code started_at} of the last item, or {@code null}
 *                    when there are no more pages
 * @param hasMore     {@code true} when another page is available
 */
public record LiveStreamPageResponse(
        List<StreamSummaryResponse> streams,
        String nextCursor,
        boolean hasMore
) {
    public static LiveStreamPageResponse of(
            List<StreamSummaryResponse> streams,
            String nextCursor,
            boolean hasMore) {
        return new LiveStreamPageResponse(streams, nextCursor, hasMore);
    }
}
