package com.streaming.stream.api.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CreateStreamRequest(
        @NotBlank @Size(max = 256) String title,
        @Size(max = 2048) String description,
        @Size(max = 64) String category,
        UUID categoryId,
        List<@Size(max = 64) String> tags,
        Integer maxViewers,
        Boolean autoArchiveChat,
        Integer chatArchiveDelayMinutes,
        @Future OffsetDateTime scheduledAt
) {}
