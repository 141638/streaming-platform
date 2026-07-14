package com.streaming.notification.api.error;

/**
 * Error envelope returned by the notification API.
 *
 * <p>The {@code code} field carries a stable, machine-readable identifier that
 * clients can branch on.
 */
public record NotificationApiError(String code, String message) {}
