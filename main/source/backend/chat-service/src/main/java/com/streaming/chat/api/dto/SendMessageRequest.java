package com.streaming.chat.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /v1/rooms/{roomKey}/messages}.
 *
 * <p>The author is derived from the JWT {@code sub} claim — never from the
 * request body. This prevents author impersonation.
 */
public record SendMessageRequest(
        @NotBlank(message = "content must not be blank")
        String content
) {
}
