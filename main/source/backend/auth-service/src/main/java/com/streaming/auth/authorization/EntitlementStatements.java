package com.streaming.auth.authorization;

import java.util.List;

/** Formats compact PBAC lines placed in the JWT {@code ent} array. */
public final class EntitlementStatements {

    private EntitlementStatements() {}

    public static String allowLine(String resourcePattern, AuthAction first, AuthAction... rest) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("allow ").append(resourcePattern);
        sb.append(' ').append(first.wireValue());
        for (AuthAction a : rest) {
            sb.append(' ').append(a.wireValue());
        }
        return sb.toString();
    }

    public static String allowLine(String resourcePattern, List<AuthAction> actions) {
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("actions must not be empty");
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append("allow ").append(resourcePattern);
        for (AuthAction a : actions) {
            sb.append(' ').append(a.wireValue());
        }
        return sb.toString();
    }

    /**
     * Builds an entitlement line using wire-format action strings (e.g. from policy.definition JSON).
     * Does not normalize spelling; callers should emit catalog-aligned tokens.
     */
    public static String allowLine(String resourcePattern, Iterable<String> actionWireValues) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("allow ").append(resourcePattern);
        int n = 0;
        for (String raw : actionWireValues) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            sb.append(' ').append(raw.trim());
            n++;
        }
        if (n == 0) {
            throw new IllegalArgumentException("actionWireValues must not be empty after trimming");
        }
        return sb.toString();
    }

    /** Example: {@code stream:session:self} with listed actions (see PBAC docs). */
    public static String streamSessionSelf(AuthAction first, AuthAction... rest) {
        return allowLine(
                AuthorizationResource.pattern(AuthResourceDomain.STREAM, AuthResourceKind.SESSION, "self"),
                first,
                rest);
    }

    /** Example service-token line: validate publish keys across sessions. */
    public static String allowStreamPublishKeyValidatePublishAll() {
        return allowLine(
                AuthorizationResource.pattern(AuthResourceDomain.STREAM, AuthResourceKind.PUBLISH_KEY, "*"),
                AuthAction.VALIDATE_PUBLISH);
    }

    public static boolean isAllowLine(String entLine) {
        return entLine != null && entLine.startsWith("allow ");
    }
}
