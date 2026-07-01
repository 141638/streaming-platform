package com.streaming.stream.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("EntitlementMatcher")
class EntitlementMatcherTest {

    private static final String SUB = "e8f9a1b2-3c4d-5e6f-7a8b-9c0d1e2f3a4b";
    private static final String OTHER_SUB = "f9a1b2c3-4d5e-6f7a-8b9c-0d1e2f3a4b5c";

    // ── helpers ──────────────────────────────────────────────────────────

    private static Jwt jwtWithEnt(List<String> ent) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("sub", SUB)
                .claim("ent", ent)
                .build();
    }

    private static Jwt jwtWithoutEnt() {
        return Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("sub", SUB)
                .build();
    }

    private static RequiredAuthority required(AuthAction action, String ownerSub) {
        return new RequiredAuthority(AuthResourceDomain.STREAM, AuthResourceKind.SESSION, action, ownerSub);
    }

    // ── self scope ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("self scope")
    class SelfScope {

        @Test
        @DisplayName("authorizes when owner matches sub")
        void authorizesWhenOwnerMatches() {
            Jwt jwt = jwtWithEnt(List.of("allow stream:session:self create read update"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isTrue();
        }

        @Test
        @DisplayName("denies when owner differs from sub")
        void deniesWhenOwnerDiffers() {
            Jwt jwt = jwtWithEnt(List.of("allow stream:session:self create read update"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, OTHER_SUB))).isFalse();
        }

        @Test
        @DisplayName("denies when owner is null")
        void deniesWhenOwnerIsNull() {
            Jwt jwt = jwtWithEnt(List.of("allow stream:session:self create read update"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, null))).isFalse();
        }

        @Test
        @DisplayName("denies when action not in line")
        void deniesWhenActionNotInLine() {
            Jwt jwt = jwtWithEnt(List.of("allow stream:session:self create read update"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.DELETE, SUB))).isFalse();
        }
    }

    // ── wildcard scope ───────────────────────────────────────────────────

    @Nested
    @DisplayName("wildcard scope (*)")
    class WildcardScope {

        @Test
        @DisplayName("authorizes regardless of owner")
        void authorizesRegardlessOfOwner() {
            Jwt jwt = jwtWithEnt(List.of("allow stream:session:* read update delete"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, OTHER_SUB))).isTrue();
        }

        @Test
        @DisplayName("authorizes with null owner")
        void authorizesWithNullOwner() {
            Jwt jwt = jwtWithEnt(List.of("allow stream:session:* read update delete"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, null))).isTrue();
        }
    }

    // ── wrong domain/kind ────────────────────────────────────────────────

    @Test
    @DisplayName("denies when domain:kind does not match")
    void deniesForWrongDomainKind() {
        Jwt jwt = jwtWithEnt(List.of("allow chat:room:* read send"));
        assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isFalse();
    }

    @Test
    @DisplayName("denies when domain matches but kind differs")
    void deniesForWrongKind() {
        Jwt jwt = jwtWithEnt(List.of("allow stream:publish-key:self validate_publish"));
        assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isFalse();
    }

    // ── empty / missing ent ──────────────────────────────────────────────

    @Test
    @DisplayName("denies when ent list is empty")
    void deniesWhenEntEmpty() {
        Jwt jwt = jwtWithEnt(List.of());
        assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isFalse();
    }

    @Test
    @DisplayName("denies when ent claim is missing")
    void deniesWhenEntMissing() {
        Jwt jwt = jwtWithoutEnt();
        assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isFalse();
    }

    // ── multiple lines ───────────────────────────────────────────────────

    @Test
    @DisplayName("matches from any line")
    void matchesFromAnyLine() {
        Jwt jwt = jwtWithEnt(List.of(
                "allow chat:room:* read send",
                "allow stream:session:self create read update lifecycle issue_key",
                "allow notification:subscription:self create read update delete"
        ));
        assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isTrue();
    }

    @Test
    @DisplayName("first matching line wins")
    void firstMatchingLineWins() {
        Jwt jwt = jwtWithEnt(List.of(
                "allow stream:session:self create read update",
                "allow stream:session:* read"
        ));
        assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isTrue();
    }

    // ── static helpers ───────────────────────────────────────────────────

    @Test
    @DisplayName("subject() extracts sub claim")
    void subjectExtractsSubClaim() {
        Jwt jwt = jwtWithEnt(List.of());
        assertThat(EntitlementMatcher.subject(jwt)).isEqualTo(SUB);
    }

    @Test
    @DisplayName("entLines() returns empty list when claim missing")
    void entLinesReturnsEmptyWhenMissing() {
        Jwt jwt = jwtWithoutEnt();
        assertThat(EntitlementMatcher.entLines(jwt)).isEmpty();
    }
}
