package com.streaming.stream.security;

// PBAC-COMMON-CANDIDATE — extract to pbac-common in Phase 6.2

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Parses the JWT {@code ent} claim (a {@code List<String>} of compact entitlement
 * lines in {@code allow <resourcePattern> <action>...} format) and matches
 * against a {@link RequiredAuthority}.
 *
 * <p>Pure logic — no Spring annotations, no reactive types. Thread-safe.
 *
 * <h3>Entitlement line format</h3>
 * <pre>{@code
 *   allow stream:session:self create read update lifecycle issue_key
 *   allow stream:session:* read update delete
 * }</pre>
 *
 * <p><strong>Scope resolution:</strong>
 * <ul>
 *   <li>{@code self} — owner must match JWT {@code sub}</li>
 *   <li>{@code *} — wildcard, always matches</li>
 *   <li>specific UUID — exact owner match</li>
 * </ul>
 */
public final class EntitlementMatcher {

    private static final String ALLOW_PREFIX = "allow ";

    private EntitlementMatcher() {
        /* utility — no instance */
    }

    /**
     * Check whether the given JWT entitles the subject to satisfy
     * {@code required}.
     *
     * @param jwt      the validated access token (carries {@code ent} and {@code sub})
     * @param required the authority required to access the resource
     * @return {@code true} if at least one matching allow-line authorizes the request
     */
    public static boolean isAuthorized(Jwt jwt, RequiredAuthority required) {
        List<String> entLines = entLines(jwt);
        if (entLines.isEmpty()) {
            return false;
        }

        String sub = subject(jwt);
        String prefix = required.domain().segment() + ":" + required.kind().segment() + ":";

        for (String line : entLines) {
            if (isEntMatched(line, prefix, required.ownerSubject(), sub, required.action().wireValue())) {
                return true;
            }
        }

        return false;
    }

    private static boolean isEntMatched(
            String ent,
            String domainKind,
            String ownerSubject,
            String sub,
            String action
    ) {
        if (ent == null || !ent.startsWith(ALLOW_PREFIX)) {
            return false;
        }
        String body = ent.substring(ALLOW_PREFIX.length()).trim();
        int firstSpace = body.indexOf(' ');
        if (firstSpace < 0) {
            return false; // no actions listed — malformed ent
        }

        String resourcePattern = body.substring(0, firstSpace);
        if (!resourcePattern.startsWith(domainKind)) {
            return false; // different domain:kind
        }

        String scope = resourcePattern.substring(domainKind.length());
        if (!scopeMatches(scope, ownerSubject, sub)) {
            return false;
        }

        String actionsPart = body.substring(firstSpace + 1).trim();
        return actionsContain(actionsPart, action);
    }

    /** Extract the {@code sub} claim from the JWT. */
    public static String subject(Jwt jwt) {
        return jwt.getSubject();
    }

    /** Extract the {@code ent} claim as a list of strings, or empty list if absent. */
    @SuppressWarnings("unchecked")
    public static List<String> entLines(Jwt jwt) {
        Object raw = jwt.getClaim("ent");
        if (raw instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    // ── private helpers ────────────────────────────────────────────────────

    private static boolean scopeMatches(String scope, String ownerSubject, String sub) {
        if ("*".equals(scope)) {
            return true;
        }
        if ("self".equals(scope)) {
            return ownerSubject != null && ownerSubject.equals(sub);
        }
        // Specific instance ID
        return ownerSubject != null && ownerSubject.equals(scope);
    }

    private static boolean actionsContain(String actionsPart, String action) {
        for (String token : actionsPart.split("\\s+")) {
            if (action.equals(token)) {
                return true;
            }
        }
        return false;
    }
}
