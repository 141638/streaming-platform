package com.streaming.stream.api.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code PATCH /v1/streams/{id}} — metadata only.
 *
 * <p>Status transitions are handled by dedicated lifecycle endpoints
 * ({@code /start}, {@code /end}, {@code /cancel}, {@code /schedule}).
 */
public record UpdateStreamRequest(
        @Size(max = 256) String title,
        @Size(max = 2048) String description,
        @Size(max = 64) String category,
        UUID categoryId,
        List<@Size(max = 64) String> tags,
        Integer maxViewers
) {}
