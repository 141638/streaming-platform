package com.streaming.stream.api.dto;

/**
 * Metadata for paginated broadcast responses.
 */
public record BroadcastPageMeta(
        long total,
        int page,
        int size
) {}
