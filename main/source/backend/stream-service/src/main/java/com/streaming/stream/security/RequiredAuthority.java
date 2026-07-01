package com.streaming.stream.security;

// PBAC-COMMON-CANDIDATE — extract to pbac-common in Phase 6.2

import org.springframework.lang.Nullable;

/**
 * Describes the authority required to access a resource.
 *
 * <p>Used with {@link EntitlementMatcher#isAuthorized} and
 * {@link StreamAuthorization#requireAccess} to check whether a JWT's
 * {@code ent} claim grants sufficient privileges.
 *
 * <h3>Example</h3>
 * <pre>{@code
 *   var required = new RequiredAuthority(
 *       AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
 *       AuthAction.READ, ownerSubject);
 *   authorization.requireAccess(jwt, required);
 * }</pre>
 *
 * @param domain       resource domain
 * @param kind         resource kind
 * @param action       PBAC action verb
 * @param ownerSubject the resource owner's {@code sub}, or {@code null} when
 *                     scope is wildcard ({@code *})
 */
public record RequiredAuthority(
        AuthResourceDomain domain,
        AuthResourceKind kind,
        AuthAction action,
        @Nullable String ownerSubject
) {}
