package com.streaming.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenSubmitRequest(@NotBlank String refreshToken) {}
