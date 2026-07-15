package com.streaming.stream.api.dto;

import java.util.UUID;

/**
 * Data needed by the viewer-facing watch page: the HLS playback URL,
 * the chat room key, and the stream's public metadata.
 *
 * <p>Non-LIVE streams (ENDED, CANCELLED, SCHEDULED, DRAFT) receive
 * {@code playUrl = null} and {@code isLive = false} rather than a 409 error,
 * so the watch page can render an archive/ended state.
 */
public record WatchResponse(
        String playUrl,
        String roomKey,
        StreamSummaryResponse stream,
        boolean isLive,
        boolean isChatArchived,
        String thumbnailUrl
) {}
