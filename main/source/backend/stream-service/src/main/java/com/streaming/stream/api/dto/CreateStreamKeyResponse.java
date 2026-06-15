package com.streaming.stream.api.dto;

public record CreateStreamKeyResponse(
        String streamId,
        String streamKey,
        String rtmpPublishUrlHint,
        String playbackUrlHint
) {
}
