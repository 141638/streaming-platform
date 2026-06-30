package com.streaming.stream.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StreamSummaryResponse(
        UUID id,
        String title,
        String status,
        String category,
        OffsetDateTime createdAt
) {
    public static StreamSummaryResponse from(
            com.streaming.stream.persistence.entity.StreamSessionEntity entity) {
        return new StreamSummaryResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getCategory(),
                entity.getCreatedAt()
        );
    }
}
