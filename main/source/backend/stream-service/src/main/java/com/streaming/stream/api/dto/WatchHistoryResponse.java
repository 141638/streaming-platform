package com.streaming.stream.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WatchHistoryResponse(
        UUID id,
        UUID streamId,
        String title,
        String status,
        String category,
        String thumbnailUrl,
        String broadcasterUsername,
        Long views,
        OffsetDateTime watchedAt,
        Long watchDurationSeconds
) {}
