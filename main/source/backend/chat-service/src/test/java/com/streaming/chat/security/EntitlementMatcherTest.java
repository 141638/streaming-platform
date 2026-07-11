package com.streaming.chat.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("EntitlementMatcher (chat)")
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
        return new RequiredAuthority(AuthResourceDomain.CHAT, AuthResourceKind.ROOM, action, ownerSub);
    }

    private static RequiredAuthority required(AuthResourceKind kind, AuthAction action, String ownerSub) {
        return new RequiredAuthority(AuthResourceDomain.CHAT, kind, action, ownerSub);
    }

    // ── self scope ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("self scope")
    class SelfScope {

        @Test
        @DisplayName("authorizes when owner matches sub")
        void authorizesWhenOwnerMatches() {
            Jwt jwt = jwtWithEnt(List.of("allow chat:room:self read send"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isTrue();
        }

        @Test
        @DisplayName("denies when owner differs from sub")
        void deniesWhenOwnerDiffers() {
            Jwt jwt = jwtWithEnt(List.of("allow chat:room:self read send"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, OTHER_SUB))).isFalse();
        }

        @Test
        @DisplayName("denies when owner is null")
        void deniesWhenOwnerIsNull() {
            Jwt jwt = jwtWithEnt(List.of("allow chat:room:self read send"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, null))).isFalse();
        }

        @Test
        @DisplayName("denies when action not in line")
        void deniesWhenActionNotInLine() {
            Jwt jwt = jwtWithEnt(List.of("allow chat:room:self read send"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.CREATE, SUB))).isFalse();
        }
    }

    // ── wildcard scope ───────────────────────────────────────────────────

    @Nested
    @DisplayName("wildcard scope (*)")
    class WildcardScope {

        @Test
        @DisplayName("authorizes regardless of owner")
        void authorizesRegardlessOfOwner() {
            Jwt jwt = jwtWithEnt(List.of("allow chat:room:* read send"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, OTHER_SUB))).isTrue();
        }

        @Test
        @DisplayName("authorizes with null owner")
        void authorizesWithNullOwner() {
            Jwt jwt = jwtWithEnt(List.of("allow chat:room:* read send"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, null))).isTrue();
        }
    }

    // ── wrong domain/kind ────────────────────────────────────────────────

    @Test
    @DisplayName("denies when kind does not match")
    void deniesForWrongKind() {
        // message-kind line must not satisfy a room-kind requirement
        Jwt jwt = jwtWithEnt(List.of("allow chat:message:self send"));
        assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isFalse();
    }

    @Test
    @DisplayName("denies when moderation kind does not match room requirement")
    void deniesForModerationKind() {
        Jwt jwt = jwtWithEnt(List.of("allow chat:moderation:* moderate"));
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
                "allow chat:moderation:* moderate",
                "allow chat:room:* read",
                "allow chat:message:* send read_history"
        ));
        assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isTrue();
    }

    @Test
    @DisplayName("first matching line wins")
    void firstMatchingLineWins() {
        Jwt jwt = jwtWithEnt(List.of(
                "allow chat:room:self read",
                "allow chat:room:* read"
        ));
        assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthAction.READ, SUB))).isTrue();
    }

    // ── flattened seed grammar (3-segment; mirrors auth V10) ─────────────

    @Nested
    @DisplayName("flattened seed grammar (mirrors auth-service V10 ent lines)")
    class FlattenedSeedGrammar {

        // Materialized ent lines the auth-service now emits per role.
        private static final List<String> VIEWER = List.of(
                "allow chat:room:* read",
                "allow chat:message:* read send read_history");
        private static final List<String> STREAMER = List.of(
                "allow chat:room:* read",
                "allow chat:message:* read send read_history",
                "allow chat:moderation:self moderate");
        private static final List<String> MODERATOR = List.of(
                "allow chat:moderation:* moderate",
                "allow chat:message:* read read_history send delete",
                "allow chat:room:* read");

        @Test
        @DisplayName("viewer may send / read / read_history on chat:message")
        void viewerMessageActions() {
            Jwt jwt = jwtWithEnt(VIEWER);
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthResourceKind.MESSAGE, AuthAction.READ, OTHER_SUB))).isTrue();
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthResourceKind.MESSAGE, AuthAction.SEND, OTHER_SUB))).isTrue();
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthResourceKind.MESSAGE, AuthAction.READ_HISTORY, OTHER_SUB))).isTrue();
        }

        @Test
        @DisplayName("viewer may not moderate")
        void viewerCannotModerate() {
            Jwt jwt = jwtWithEnt(VIEWER);
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthResourceKind.MODERATION, AuthAction.MODERATE, SUB))).isFalse();
        }

        @Test
        @DisplayName("streamer self-moderates own room but not another owner's")
        void streamerSelfModeration() {
            Jwt jwt = jwtWithEnt(STREAMER);
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthResourceKind.MODERATION, AuthAction.MODERATE, SUB))).isTrue();
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthResourceKind.MODERATION, AuthAction.MODERATE, OTHER_SUB))).isFalse();
        }

        @Test
        @DisplayName("moderator moderates any owner's room (wildcard scope)")
        void moderatorWildcardModeration() {
            Jwt jwt = jwtWithEnt(MODERATOR);
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthResourceKind.MODERATION, AuthAction.MODERATE, OTHER_SUB))).isTrue();
        }

        @Test
        @DisplayName("regression: old 4-segment chat:message:room:* does NOT authorize send")
        void oldFourSegmentDoesNotMatch() {
            // The pre-V10 grammar: matcher reads scope as "room:*" (not "*"), so it
            // matches neither self/*/uuid — this is the exact bug V10 fixed.
            Jwt jwt = jwtWithEnt(List.of("allow chat:message:room:* send"));
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthResourceKind.MESSAGE, AuthAction.SEND, OTHER_SUB))).isFalse();
            assertThat(EntitlementMatcher.isAuthorized(jwt, required(AuthResourceKind.MESSAGE, AuthAction.SEND, SUB))).isFalse();
        }
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
