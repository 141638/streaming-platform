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
        Long viewerCount,
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
                null, // viewerCount — populated at query time from Redis
                entity.getCreatedAt(),
                entity.getScheduledAt(),
                entity.getBroadcasterUsername()
        );
    }

    /** Returns a copy of this response with the given viewer count. */
    public StreamSummaryResponse withViewerCount(Long viewerCount) {
        return new StreamSummaryResponse(
                id, title, status, category, categoryId, tags, thumbnailUrl,
                views, viewerCount, createdAt, scheduledAt, broadcasterUsername);
    }
}
