package com.streaming.notification.api.dto;

/**
 * Request body for creating or updating a notification preference.
 *
 * @param channel   delivery channel ({@code "in_app"}, {@code "email"}, {@code "push"})
 * @param topicGlob category filter pattern (e.g. {@code "STREAM_*"}), or {@code null} for all
 */
public record PreferenceRequest(
        String channel,
        String topicGlob
) {}
