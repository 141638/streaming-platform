package com.streaming.stream.api.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * Request body for {@code POST /v1/streams/{id}/schedule}.
 */
public record ScheduleStreamRequest(
        @NotNull @Future OffsetDateTime scheduledAt
) {}
