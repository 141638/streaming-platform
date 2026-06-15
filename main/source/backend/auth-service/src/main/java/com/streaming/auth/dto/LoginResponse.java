package com.streaming.auth.dto;

public record LoginResponse(
        String status,
        String message,
        String accessToken,
        long expiresInSeconds,
        String policyVersion,
        String refreshToken,
        long refreshExpiresInSeconds) {}
