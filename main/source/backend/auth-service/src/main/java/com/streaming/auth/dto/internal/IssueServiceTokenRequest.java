package com.streaming.auth.dto.internal;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /v1/internal/service-tokens}.
 */
public record IssueServiceTokenRequest(
        @NotBlank String principalSubject
) {}
