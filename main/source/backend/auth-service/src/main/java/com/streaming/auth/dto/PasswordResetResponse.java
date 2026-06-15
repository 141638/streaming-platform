package com.streaming.auth.dto;

public record PasswordResetResponse(
        String status,
        String message
) {
}
