package com.streaming.stream.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StreamResponse(
        UUID id,
        String title,
        String description,
        String category,
        Integer maxViewers,
        String status,
        String broadcasterSubject,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime endedAt
) {
    public static StreamResponse from(com.streaming.stream.persistence.entity.StreamSessionEntity entity) {
        return new StreamResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getCategory(),
                entity.getMaxViewers(),
                entity.getStatus().wireValue(),
                entity.getBroadcasterSubject(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getEndedAt()
        );
    }
}
