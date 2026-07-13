package com.streaming.stream.api.dto;

import java.util.List;

/**
 * Paginated response for the broadcasts endpoint.
 */
public record BroadcastPageResponse(
        List<StreamSummaryResponse> data,
        BroadcastPageMeta meta
) {
    public static BroadcastPageResponse of(List<StreamSummaryResponse> data, long total, int page, int size) {
        return new BroadcastPageResponse(data, new BroadcastPageMeta(total, page, size));
    }
}
