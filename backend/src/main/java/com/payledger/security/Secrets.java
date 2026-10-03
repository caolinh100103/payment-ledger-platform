package com.payledger.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random bearer secrets (refresh tokens, API keys) and the hash under which they are stored. */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {
    }

    /** {@code bytes} random bytes, base64url-encoded without padding. */
    public static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static SecureRandom secureRandom() {
        return RANDOM;
    }

    /**
     * SHA-256, hex-encoded. Enough for secrets with 128+ bits of entropy: nobody can guess one, so the slow,
     * salted hashing that passwords need would add cost without adding security.
     */
    public static String sha256(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
