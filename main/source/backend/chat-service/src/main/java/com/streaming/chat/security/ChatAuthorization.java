package com.streaming.chat.security;

// PBAC-COMMON-CANDIDATE — extract to pbac-common in Phase 6.2

import com.streaming.chat.config.ChatPbacProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>Ships dark:</b> when {@code chat.pbac.enabled=false} (the default),
 * {@link #requireAccess} returns {@link Mono#empty()} immediately without
 * consulting the matcher. This lets the enforcement wiring land in production
 * before auth-service is confirmed to emit compatible chat {@code ent} lines.
 */
@Component
@RequiredArgsConstructor
public class ChatAuthorization {

    private static final Logger log = LoggerFactory.getLogger(ChatAuthorization.class);

    private final ChatPbacProperties properties;

    /**
     * Emit a startup WARN when enforcement is disabled so an operator can never
     * unknowingly run with PBAC off in production (e.g. {@code CHAT_PBAC_ENABLED}
     * unset). Dark-launch is intentional but should be visible in the logs.
     */
    @PostConstruct
    void warnIfDisabled() {
        if (!properties.enabled()) {
            log.warn("PBAC enforcement is DISABLED (chat.pbac.enabled=false / CHAT_PBAC_ENABLED unset) — "
                    + "chat authorization checks are bypassed. Enable once auth-service emits compatible ent lines.");
        }
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
        if (EntitlementMatcher.isAuthorized(jwt, required)) {
            return Mono.empty();
        }
        return Mono.error(new ChatAccessDeniedException(required, EntitlementMatcher.subject(jwt)));
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
