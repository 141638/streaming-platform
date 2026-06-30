package com.streaming.stream.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStreamRequest(
        @NotBlank @Size(max = 256) String title,
        @Size(max = 2048) String description,
        @Size(max = 64) String category,
        Integer maxViewers
) {}
