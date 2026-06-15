package com.streaming.auth.dto.jwt;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Small subject attributes for local ABAC-style conditions in services (not full profile).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubjectAttributes(
        @JsonProperty("roles") List<String> roles,
        @JsonProperty("tier") String tier,
        @JsonProperty("verified_streamer") Boolean verifiedStreamer
) {}
