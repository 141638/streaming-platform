package com.streaming.notification.api.dto;

/**
 * Unread notification count for the bell badge.
 */
public record UnreadCountResponse(long count) {

    public static UnreadCountResponse of(long count) {
        return new UnreadCountResponse(count);
    }
}
