package com.streaming.stream.api.dto;

import com.streaming.stream.persistence.entity.StreamCategoryEntity;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        String slug,
        int displayOrder
) {
    public static CategoryResponse from(StreamCategoryEntity entity) {
        return new CategoryResponse(
                entity.getId(),
                entity.getName(),
                entity.getSlug(),
                entity.getDisplayOrder()
        );
    }
}
