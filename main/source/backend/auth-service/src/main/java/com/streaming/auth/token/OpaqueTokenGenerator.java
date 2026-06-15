package com.streaming.auth.token;

import java.security.SecureRandom;
import java.util.Base64;

/** URL-safe opaque token for refresh credential (plaintext returned once). */
public final class OpaqueTokenGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** ~512 bits entropy (64 bytes URL-safe unpadded Base64). */
    private static final int BYTES = 48;

    private OpaqueTokenGenerator() {}

    public static String newRefreshSecret() {
        byte[] raw = new byte[BYTES];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
