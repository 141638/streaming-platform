package com.streaming.auth.authorization;

/** Builds canonical resource patterns for policies and JWT materialization. */
public final class AuthorizationResource {

    private AuthorizationResource() {}

    public static String pattern(AuthResourceDomain domain, AuthResourceKind kind, String scope) {
        return domain.segment() + ":" + kind.segment() + ":" + scope;
    }
}
