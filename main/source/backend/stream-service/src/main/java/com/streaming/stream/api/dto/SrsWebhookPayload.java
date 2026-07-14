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
    /**
     * Extract the publish token from the {@code param} query string.
     *
     * <p>SRS passes the RTMP query string in the {@code param} field. Some
     * versions include a leading {@code ?} (e.g. {@code "?token=eyJ..."}),
     * others send the raw query string (e.g. {@code "token=eyJ..."}).
     * As a fallback, the {@code stream} field is also checked — some SRS
     * configurations pass the full stream key including query string as the
     * stream name (e.g. {@code "live/abc123?token=eyJ..."}).
     */
    public String extractToken() {
        String token = extractFrom(param);
        if (token != null) {
            return token;
        }
        // Fallback: some SRS versions put the full key in the stream field
        return extractFrom(stream);
    }

    private static String extractFrom(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        // Strip leading '?' if present (some SRS versions include it)
        String normalized = source.startsWith("?") ? source.substring(1) : source;
        for (String part : normalized.split("\\?")) {
            if (part.startsWith("token=")) {
                String token = part.substring("token=".length());
                return token.isBlank() ? null : token;
            }
        }
        return null;
    }
}
