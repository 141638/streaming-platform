package com.streaming.gateway.filter;

/**
 * Serialized form of a cached idempotent response stored in Redis.
 *
 * @param status      HTTP status code (e.g. 201)
 * @param contentType MIME type of the response body (e.g. "application/json")
 * @param body        Base64-encoded response body (may be empty for 204-style responses)
 */
record CachedResponse(
        int status,
        String contentType,
        String body
) {
}
