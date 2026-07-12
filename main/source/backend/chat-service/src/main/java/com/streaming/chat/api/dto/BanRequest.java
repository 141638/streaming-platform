package com.streaming.chat.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /v1/rooms/{roomKey}/bans}.
 *
 * <p>The moderator identity (who is issuing the ban) is derived from the JWT
 * {@code sub} claim — never from the request body.
 *
 * @param bannedSubject   the JWT {@code sub} of the user to ban (required)
 * @param bannedUsername  optional denormalized display name of the banned user,
 *                        supplied by the client (which already has it from the
 *                        message context) so the roster can show a name, not a sub
 * @param reason          optional human-readable reason, stored for audit
 * @param durationSeconds optional ban duration in seconds; {@code null} means a
 *                        permanent ban, a positive value means a temporary ban
 *                        that lapses at {@code now + durationSeconds}
 */
public record BanRequest(
        @NotBlank(message = "bannedSubject must not be blank")
        @Size(max = 128, message = "bannedSubject must not exceed 128 characters")
        String bannedSubject,
        @Size(max = 128, message = "bannedUsername must not exceed 128 characters")
        String bannedUsername,
        @Size(max = 500, message = "reason must not exceed 500 characters")
        String reason,
        @Positive(message = "durationSeconds must be positive")
        @Max(value = 315_360_000L, message = "durationSeconds must not exceed 10 years")
        Long durationSeconds
) {
}
