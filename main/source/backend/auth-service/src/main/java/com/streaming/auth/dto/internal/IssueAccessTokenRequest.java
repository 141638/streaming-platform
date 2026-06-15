package com.streaming.auth.dto.internal;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record IssueAccessTokenRequest(@NotNull UUID userId) {}
