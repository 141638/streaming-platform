package com.streaming.stream.security;

import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Static helpers for reading PBAC {@code attr} claim values from a JWT.
 *
 * <p>Ported from chat-service (which only had {@code username()}).
 * Planned extraction target for {@code pbac-common} (Phase 6.2).
 * Until then, kept as a tiny static utility to avoid inline map-casting
 * at every call site.
 */
public final class JwtAttr {

    private JwtAttr() { /* utility class */ }

    /**
     * Read the {@code username} convenience claim from the JWT attr map.
     *
     * @param jwt the validated access token
     * @return the username, or {@code null} if attr is absent or the key is missing
     */
    public static String username(Jwt jwt) {
        Map<String, Object> attr = jwt.getClaimAsMap("attr");
        if (attr == null) {
            return null;
        }
        Object value = attr.get("username");
        return value instanceof String s ? s : null;
    }

    /**
     * Read the {@code verified_streamer} claim from the JWT attr map.
     *
     * @param jwt the validated access token
     * @return the verified flag, or {@code null} if attr is absent or the key is missing
     */
    public static Boolean verifiedStreamer(Jwt jwt) {
        Map<String, Object> attr = jwt.getClaimAsMap("attr");
        if (attr == null) {
            return null;
        }
        Object value = attr.get("verified_streamer");
        return value instanceof Boolean b ? b : null;
    }
}
