package com.streaming.notification.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for creating or updating a notification preference.
 *
 * @param channel   delivery channel ({@code "in_app"}, {@code "email"}, {@code "push"})
 * @param topicGlob category filter pattern (e.g. {@code "STREAM_*"}), or {@code null} for all
 */
public record PreferenceRequest(
        @NotBlank String channel,
        String topicGlob
) {}
