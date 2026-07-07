package com.streaming.stream.api.dto;

/**
 * Body sent by SRS {@code http_hooks} on publish/unpublish events.
 *
 * <p>The {@code param} field contains the query string from the RTMP URL,
 * e.g. {@code "token=eyJhbGciOiJIUzI1NiJ9..."}.
 */
public record SrsWebhookPayload(
        String action,
        String stream,
        String param
) {
    /** Extract the publish token from the {@code param} query string. */
    public String extractToken() {
        if (param == null || param.isBlank()) {
            return null;
        }
        for (String part : param.split("&")) {
            if (part.startsWith("token=")) {
                return part.substring("token=".length());
            }
        }
        return null;
    }
}
