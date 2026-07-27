package com.streaming.pbac;

import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Static helpers for reading PBAC {@code attr} claim values from a JWT.
 *
 * <p>Avoids inline map-casting at every call site and centralizes the
 * {@code attr} claim shape so changes are made once.
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

    /**
     * Read the {@code roles} claim from the JWT attr map.
     *
     * @param jwt the validated access token
     * @return the roles list, or an empty list if attr is absent or the key is missing
     */
    @SuppressWarnings("unchecked")
    public static List<String> roles(Jwt jwt) {
        Map<String, Object> attr = jwt.getClaimAsMap("attr");
        if (attr == null) {
            return List.of();
        }
        Object value = attr.get("roles");
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    /**
     * Read the {@code tier} claim from the JWT attr map.
     *
     * @param jwt the validated access token
     * @return the tier string, or {@code null} if attr is absent or the key is missing
     */
    public static String tier(Jwt jwt) {
        Map<String, Object> attr = jwt.getClaimAsMap("attr");
        if (attr == null) {
            return null;
        }
        Object value = attr.get("tier");
        return value instanceof String s ? s : null;
    }
}
