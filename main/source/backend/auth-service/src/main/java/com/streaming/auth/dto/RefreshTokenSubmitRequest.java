package com.streaming.auth.dto;

import jakarta.annotation.Nullable;

public record RefreshTokenSubmitRequest(@Nullable String refreshToken) {}
