package com.streaming.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over UTF-8 token string for persisted lookup (opaque refresh tokens). */
public final class OpaqueTokenHasher {

    private OpaqueTokenHasher() {}

    public static byte[] sha256Utf8(String opaqueToken) {
        if (opaqueToken == null) {
            throw new IllegalArgumentException("token is null");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
        return digest.digest(opaqueToken.getBytes(StandardCharsets.UTF_8));
    }

    public static String toHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
