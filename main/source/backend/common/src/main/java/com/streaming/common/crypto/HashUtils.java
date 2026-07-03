package com.streaming.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Low-level cryptographic hash utilities shared across services.
 *
 * <p>This module has zero framework dependencies — it is plain Java 21
 * and can be used from any service without pulling in Spring or other
 * transitive dependencies.
 */
public final class HashUtils {

    private HashUtils() {}

    /**
     * Returns the raw SHA-256 digest of {@code input} interpreted as UTF-8.
     *
     * @param input the string to hash; must not be null
     * @return 32-byte SHA-256 digest
     * @throws IllegalArgumentException if input is null
     */
    public static byte[] sha256(String input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Returns the lowercase hex-encoded SHA-256 digest of {@code input}.
     * Convenience wrapper around {@link #sha256(String)}.
     */
    public static String sha256Hex(String input) {
        return HexFormat.of().formatHex(sha256(input));
    }
}
