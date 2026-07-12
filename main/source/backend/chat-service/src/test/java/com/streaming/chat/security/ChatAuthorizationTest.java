package com.streaming.chat.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.chat.config.ChatPbacProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ChatAuthorization#hasCapability} — the flag-independent
 * capability query used to surface signals like {@code viewerCanModerate}.
 *
 * <p>Unlike {@code requireAccess}, {@code hasCapability} must NOT be gated by
 * {@code chat.pbac.enabled}: capability display is a UI affordance concern that
 * is orthogonal to enforcement dark-launch. These tests pin that both flag
 * states yield identical answers, and that a null principal is answered
 * {@code false} rather than throwing.
 */
@DisplayName("ChatAuthorization.hasCapability (flag-independent capability query)")
@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class ChatAuthorizationTest {

    private static final String OWNER_SUB = "e8f9a1b2-3c4d-5e6f-7a8b-9c0d1e2f3a4b";
    private static final String OTHER_SUB = "f9a1b2c3-4d5e-6f7a-8b9c-0d1e2f3a4b5c";

    private static ChatAuthorization authorization(boolean enabled) {
        return new ChatAuthorization(new ChatPbacProperties(enabled));
    }

    private static Jwt jwt(String sub, List<String> ent) {
        var builder = Jwt.withTokenValue("test-token").header("alg", "HS256").claim("sub", sub);
        if (ent != null) {
            builder.claim("ent", ent);
        }
        return builder.build();
    }

    private static RequiredAuthority moderate(String ownerSub) {
        return new RequiredAuthority(
                AuthResourceDomain.CHAT, AuthResourceKind.MODERATION, AuthAction.MODERATE, ownerSub);
    }

    @ParameterizedTest(name = "chat.pbac.enabled={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("owner holding self-scoped moderate is capable regardless of the flag")
    void ownerSelfModerateIsFlagIndependent(boolean enabled) {
        Jwt owner = jwt(OWNER_SUB, List.of("allow chat:moderation:self moderate"));
        StepVerifier.create(authorization(enabled).hasCapability(owner, moderate(OWNER_SUB)))
                .expectNext(true)
                .verifyComplete();
    }

    @ParameterizedTest(name = "chat.pbac.enabled={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("staff holding wildcard moderate is capable regardless of the flag")
    void staffWildcardModerateIsFlagIndependent(boolean enabled) {
        Jwt staff = jwt(OTHER_SUB, List.of("allow chat:moderation:* moderate"));
        StepVerifier.create(authorization(enabled).hasCapability(staff, moderate(OWNER_SUB)))
                .expectNext(true)
                .verifyComplete();
    }

    @ParameterizedTest(name = "chat.pbac.enabled={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("plain viewer is not capable regardless of the flag")
    void viewerNotCapableIsFlagIndependent(boolean enabled) {
        Jwt viewer = jwt(OTHER_SUB, List.of("allow chat:room:* read", "allow chat:message:* read send"));
        StepVerifier.create(authorization(enabled).hasCapability(viewer, moderate(OWNER_SUB)))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("null principal yields false without erroring")
    void nullPrincipalIsFalse() {
        StepVerifier.create(authorization(false).hasCapability(null, moderate(OWNER_SUB)))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("requireAccess with a null principal errors (not NPE) when enforcement is enabled")
    void requireAccessNullPrincipalErrorsWhenEnabled() {
        StepVerifier.create(authorization(true).requireAccess(null, moderate(OWNER_SUB)))
                .expectError(ChatAuthorization.ChatAccessDeniedException.class)
                .verify();
    }

    @Test
    @DisplayName("requireAccess with a null principal is a no-op while enforcement ships dark")
    void requireAccessNullPrincipalNoOpWhenDisabled() {
        StepVerifier.create(authorization(false).requireAccess(null, moderate(OWNER_SUB)))
                .verifyComplete();
    }
}
