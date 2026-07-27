package com.streaming.chat.security;

// PBAC-COMMON-CANDIDATE — extract to pbac-common in Phase 6.2

import com.streaming.chat.config.ChatPbacProperties;
import com.streaming.pbac.EntitlementMatcher;
import com.streaming.pbac.RequiredAuthority;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reactive enforcement wrapper around {@link EntitlementMatcher}.
 *
 * <p>Use in service-layer reactive pipelines to guard access to resources:
 *
 * <pre>{@code
 *   var required = new RequiredAuthority(
 *       AuthResourceDomain.CHAT, AuthResourceKind.MESSAGE, AuthAction.SEND,
 *       room.getBroadcasterSubject());
 *   authorization.requireAccess(jwt, required)
 *       .then(persistMessage(...));
 * }</pre>
 *
 * <p>PBAC enforcement is gated by {@code chat.pbac.enabled}. In development
 * ({@code dev} profile active), disabling it emits a visible WARN. In any other
 * profile (production, staging), disabling it is a hard startup failure — PBAC
 * must be enabled.
 */
@Component
@RequiredArgsConstructor
public class ChatAuthorization {

    private static final Logger log = LoggerFactory.getLogger(ChatAuthorization.class);

    private final ChatPbacProperties properties;

    @Autowired(required = false)
    private Environment environment;

    /**
     * Validate that PBAC enforcement is enabled in non-dev environments.
     *
     * <p>In development ({@code dev} profile active or no Spring context), a
     * disabled flag emits a visible WARN so dark-launch is still possible for
     * local testing. In any other profile (production, staging), disabled PBAC
     * is a hard startup failure — the application will not start until
     * {@code chat.pbac.enabled=true} / {@code CHAT_PBAC_ENABLED=true}.
     */
    @PostConstruct
    void enforceOrWarn() {
        if (!properties.enabled()) {
            if (isProductionProfile()) {
                throw new IllegalStateException(
                        "chat.pbac.enabled must be true in production profiles. "
                                + "Set CHAT_PBAC_ENABLED=true or chat.pbac.enabled=true.");
            }
            log.warn("PBAC enforcement is DISABLED (chat.pbac.enabled=false / CHAT_PBAC_ENABLED unset) — "
                    + "chat authorization checks are bypassed. Enable once auth-service emits compatible ent lines.");
        }
    }

    /**
     * Determine whether the application is running in a non-dev profile.
     * When no Spring {@link Environment} is available (unit tests without
     * Spring context), defaults to {@code false} — safe, warn-only.
     */
    private boolean isProductionProfile() {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("dev".equals(profile)) {
                return false;
            }
        }
        // No "dev" profile active — treat as production
        return true;
    }

    /**
     * Verify that the JWT satisfies {@code required}.
     *
     * @return {@link Mono#empty()} when authorized (or when enforcement is
     *         disabled); a {@link Mono} error carrying
     *         {@link ChatAccessDeniedException} otherwise
     */
    public Mono<Void> requireAccess(Jwt jwt, RequiredAuthority required) {
        if (!properties.enabled()) {
            return Mono.empty();
        }
        if (jwt == null) {
            return Mono.error(new ChatAccessDeniedException(required, "anonymous"));
        }
        if (EntitlementMatcher.isAuthorized(jwt, required)) {
            return Mono.empty();
        }
        return Mono.error(new ChatAccessDeniedException(required, EntitlementMatcher.subject(jwt)));
    }

    /**
     * Answer whether the JWT <em>holds</em> the given authority — a pure
     * capability query, <b>independent of the {@code chat.pbac.enabled}
     * dark-launch flag</b>.
     *
     * <p>Unlike {@link #requireAccess}, this never short-circuits on the flag and
     * never errors: it is used to surface capability signals to clients (e.g.
     * {@code RoomResponse.viewerCanModerate}) so the UI can render moderator
     * affordances even while enforcement ships dark. Enforcement gating lives in
     * {@link #requireAccess}; capability display must not depend on it.
     *
     * <p>A {@code null} principal (genuinely unauthenticated request) yields
     * {@code false} rather than throwing.
     *
     * @return {@code Mono.just(true)} when the {@code ent} claim authorizes
     *         {@code required}; {@code Mono.just(false)} otherwise
     */
    public Mono<Boolean> hasCapability(Jwt jwt, RequiredAuthority required) {
        if (jwt == null) {
            return Mono.just(false);
        }
        return Mono.just(EntitlementMatcher.isAuthorized(jwt, required));
    }

    // ── exception type ─────────────────────────────────────────────────────

    /**
     * Thrown when the caller's JWT does not contain an entitlement line
     * authorizing the requested action on the target resource.
     */
    public static final class ChatAccessDeniedException extends RuntimeException {

        public ChatAccessDeniedException(RequiredAuthority required, String callerSubject) {
            super(String.format(
                    "Access denied: %s:%s/%s (owner=%s) (caller=%s)",
                    required.domain().segment(), required.kind().segment(), required.action().wireValue(),
                    required.ownerSubject() != null ? required.ownerSubject() : "null",
                    callerSubject));
        }
    }
}
