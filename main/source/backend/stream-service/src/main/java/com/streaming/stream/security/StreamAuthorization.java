package com.streaming.stream.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reactive enforcement wrapper around {@link EntitlementMatcher}.
 *
 * <p>Use in service-layer reactive pipelines to guard access to resources:
 *
 * <pre>{@code
 *   var required = new RequiredAuthority("stream", "session", "read", entity.getBroadcasterSubject());
 *   repository.findById(id)
 *     .flatMap(entity -> authorization
 *         .requireAccess(jwt, required)
 *         .thenReturn(entity))
 *     .map(StreamResponse::from);
 * }</pre>
 */
@Component
public class StreamAuthorization {

    /**
     * Verify that the JWT satisfies {@code required}.
     *
     * @return {@link Mono#empty()} when authorized; a {@link Mono} error carrying
     *         {@link StreamAccessDeniedException} otherwise
     */
    public Mono<Void> requireAccess(Jwt jwt, RequiredAuthority required) {
        if (EntitlementMatcher.isAuthorized(jwt, required)) {
            return Mono.empty();
        }

        return Mono.error(new StreamAccessDeniedException(required, EntitlementMatcher.subject(jwt)));
    }

    // ── exception type ─────────────────────────────────────────────────────

    /**
     * Thrown when the caller's JWT does not contain an entitlement line
     * authorizing the requested action on the target resource.
     */
    public static final class StreamAccessDeniedException extends RuntimeException {

        public StreamAccessDeniedException(RequiredAuthority required, String callerSubject) {
            super(String.format(
                    "Access denied: %s:%s/%s (owner=%s) (caller=%s)",
                    required.domain().segment(), required.kind().segment(), required.action().wireValue(),
                    required.ownerSubject() != null ? required.ownerSubject() : "null",
                    callerSubject));
        }
    }
}
