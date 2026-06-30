package com.streaming.stream.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PublishKeyResponse(
        UUID streamId,
        String publishKey,
        String rtmpUrl,
        OffsetDateTime expiresAt
) {}
