package com.streaming.notification.api.dto;

/**
 * Request body for partially updating a notification preference.
 *
 * <p>Both fields are optional — only the provided fields are applied.
 * This replaces the raw {@code Map<String, Object>} body previously
 * accepted by the PATCH endpoint.
 *
 * @param active    whether the preference is active
 * @param topicGlob updated category filter pattern (e.g. {@code "STREAM_*"})
 */
public record UpdatePreferenceRequest(
        Boolean active,
        String topicGlob
) {}
