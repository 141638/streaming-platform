package com.streaming.chat.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code PATCH /v1/rooms/{roomKey}/bans/{subject}} — re-base an
 * existing ban's duration in place (instead of unban + re-ban).
 *
 * <p>The new expiry is computed off <em>now</em>: {@code expiresAt = now +
 * durationSeconds}. A {@code null} {@code durationSeconds} promotes the ban to
 * permanent. Validation mirrors {@link BanRequest#durationSeconds()} (positive,
 * capped at 10 years).
 *
 * @param durationSeconds new ban duration in seconds; {@code null} = permanent
 */
public record BanDurationRequest(
        @Positive(message = "durationSeconds must be positive")
        @Max(value = 315_360_000L, message = "durationSeconds must not exceed 10 years")
        Long durationSeconds
) {
}
