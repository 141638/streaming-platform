package com.streaming.auth.dto.jwt;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Access-token claims for user flows: standard JWT fields plus PBAC materialization
 * ({@code ver}, {@code pv}, {@code ent}, {@code attr}) as documented in {@code docs/PBAC-AUTHORIZATION.md}.
 * <p>
 * Wire names follow JWT conventions; {@code attr} maps use snake_case for JSON interoperability.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamingAccessTokenPayload(
        @JsonProperty("iss") String issuer,
        @JsonProperty("aud") String audience,
        @JsonProperty("sub") String subject,
        @JsonProperty("exp") long expiresAtEpochSeconds,
        @JsonProperty("iat") long issuedAtEpochSeconds,
        @JsonProperty("jti") String tokenId,

        @JsonProperty("nbf") Long notBeforeEpochSeconds,
        @JsonProperty("typ") String type,

        @JsonProperty("ver") int entitlementGrammarVersion,
        @JsonProperty("pv") String policyVersion,
        @JsonProperty("ent") List<String> entitlements,
        @JsonProperty("attr") SubjectAttributes attributes
) {
    public StreamingAccessTokenPayload {
        if (entitlements != null) {
            entitlements = List.copyOf(entitlements);
        }
    }
}
