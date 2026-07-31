package com.streaming.notification.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for creating a subscription (following a target).
 *
 * @param targetType the kind of target ({@code "CHANNEL"}, {@code "CHAT_ROOM"}, {@code "STREAM_SESSION"})
 * @param targetId   the target identifier (broadcaster subject, room key, stream ID)
 */
public record SubscriptionRequest(
        @NotBlank String targetType,
        @NotBlank String targetId
) {}
