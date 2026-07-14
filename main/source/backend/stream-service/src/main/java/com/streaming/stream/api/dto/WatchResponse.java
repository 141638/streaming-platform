package com.streaming.stream.api.dto;

import java.util.UUID;

/**
 * Data needed by the viewer-facing watch page: the HLS playback URL,
 * the chat room key, and the stream's public metadata.
 */
public record WatchResponse(
        String playUrl,
        String roomKey,
        StreamSummaryResponse stream
) {}
