package com.payledger.security.apikey;

import com.payledger.security.Secrets;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * The shape of an API key, modelled on GitHub's token format: {@code plk_} + 32 random base62 characters + a 6-character
 * base62 CRC32 checksum of the random part, e.g. {@code plk_6Hq2...k9Xa0aB3cD}.
 *
 * <ul>
 *   <li>The prefix makes a leaked key recognisable: secret scanners (GitHub, GitGuardian) and log filters can
 *       match it with a regex instead of guessing at random-looking strings.</li>
 *   <li>The checksum lets them, and this server, reject a mistyped or made-up key without a database lookup.</li>
 *   <li>Base62 has no {@code _} or {@code -}, so the key survives double-click selection and URL encoding intact.</li>
 * </ul>
 *
 * <p>32 base62 characters are about 190 random bits, beyond any guessing.
 */
final class ApiKeyFormat {

    static final String PREFIX = "plk_";
    private static final int RANDOM_LENGTH = 32;
    private static final int CHECKSUM_LENGTH = 6;
    private static final int DISPLAY_LENGTH = PREFIX.length() + 8;
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern SHAPE = Pattern.compile(
            "^" + PREFIX + "[0-9A-Za-z]{" + (RANDOM_LENGTH + CHECKSUM_LENGTH) + "}$");

    private ApiKeyFormat() {
    }

    static String generate() {
        StringBuilder random = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            random.append(BASE62.charAt(Secrets.secureRandom().nextInt(BASE62.length())));
        }
        return PREFIX + random + checksum(random.toString());
    }

    static boolean isWellFormed(String key) {
        if (key == null || !SHAPE.matcher(key).matches()) {
            return false;
        }
        String random = key.substring(PREFIX.length(), PREFIX.length() + RANDOM_LENGTH);
        return key.endsWith(checksum(random));
    }

    /** Enough of the key to recognise it in a listing, far too little to use it. */
    static String displayPrefix(String key) {
        return key.substring(0, DISPLAY_LENGTH);
    }

    private static String checksum(String random) {
        CRC32 crc = new CRC32();
        crc.update(random.getBytes(StandardCharsets.US_ASCII));
        long value = crc.getValue();
        char[] digits = new char[CHECKSUM_LENGTH];
        for (int i = CHECKSUM_LENGTH - 1; i >= 0; i--) {
            digits[i] = BASE62.charAt((int) (value % BASE62.length()));
            value /= BASE62.length();
        }
        return new String(digits);
    }
}
