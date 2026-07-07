package com.streaming.stream.api.dto;

import com.streaming.stream.persistence.entity.StreamSessionEntity;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record StreamResponse(
        UUID id,
        String title,
        String description,
        String category,
        UUID categoryId,
        List<String> tags,
        Integer maxViewers,
        String status,
        String broadcasterSubject,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime startedAt,
        OffsetDateTime scheduledAt,
        OffsetDateTime endedAt
) {
    public static StreamResponse from(StreamSessionEntity entity) {
        return new StreamResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getCategory(),
                entity.getCategoryId(),
                entity.getTags() != null ? List.copyOf(Arrays.asList(entity.getTags())) : List.of(),
                entity.getMaxViewers(),
                entity.getStatus().wireValue(),
                entity.getBroadcasterSubject(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getScheduledAt(),
                entity.getEndedAt()
        );
    }
}
