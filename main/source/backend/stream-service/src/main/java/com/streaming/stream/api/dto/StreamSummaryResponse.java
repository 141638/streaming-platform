package com.streaming.stream.api.dto;

import com.streaming.stream.persistence.entity.StreamSessionEntity;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record StreamSummaryResponse(
        UUID id,
        String title,
        String status,
        String category,
        UUID categoryId,
        List<String> tags,
        String thumbnailUrl,
        Long views,
        OffsetDateTime createdAt,
        OffsetDateTime scheduledAt,
        String broadcasterUsername
) {
    public static StreamSummaryResponse from(StreamSessionEntity entity) {
        return new StreamSummaryResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getStatus().wireValue(),
                entity.getCategory(),
                entity.getCategoryId(),
                entity.getTags() != null ? List.copyOf(Arrays.asList(entity.getTags())) : List.of(),
                entity.getThumbnailUrl(),
                entity.getViews(),
                entity.getCreatedAt(),
                entity.getScheduledAt(),
                entity.getBroadcasterUsername()
        );
    }
}
