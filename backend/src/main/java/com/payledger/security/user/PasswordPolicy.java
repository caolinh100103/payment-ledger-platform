package com.payledger.security.user;

import com.payledger.common.error.BusinessRuleViolationException;

import java.util.List;
import java.util.Locale;

/**
 * NIST SP 800-63B-4 (2025): length instead of complexity. A password that is the only authentication factor must
 * have at least 15 characters, and at least 64 must be allowed. Composition rules ("one digit, one symbol") are
 * barred because they push people towards predictable patterns, and passwords are never expired on a schedule.
 * Passwords that contain context-specific words (the service name, the username) are rejected.
 *
 * <p>Characters are Unicode code points, so a Vietnamese passphrase with diacritics counts as it reads.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 15;
    static final int MAX_LENGTH = 64;

    private static final List<String> CONTEXT_WORDS = List.of("payledger");

    private PasswordPolicy() {
    }

    static void check(String username, String password) {
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw violation("A password must have between " + MIN_LENGTH + " and " + MAX_LENGTH
                    + " characters; a passphrase of a few words is easiest to remember");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (lower.contains(UserAccount.normalize(username))) {
            throw violation("A password must not contain the username");
        }
        if (CONTEXT_WORDS.stream().anyMatch(lower::contains)) {
            throw violation("A password must not contain the name of the service");
        }
    }

    private static BusinessRuleViolationException violation(String message) {
        return new BusinessRuleViolationException("PASSWORD_POLICY_VIOLATION", message);
    }
}
