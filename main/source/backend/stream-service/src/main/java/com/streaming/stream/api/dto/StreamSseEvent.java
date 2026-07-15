package com.streaming.stream.api.dto;

import java.util.UUID;

public record StreamSseEvent(
        String type,
        UUID streamId,
        String status,
        Long viewerCount
) {}
