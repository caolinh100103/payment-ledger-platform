package com.payledger.security.user;

import com.payledger.security.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountTest {

    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final Instant T0 = Instant.parse("2026-10-03T08:00:00Z");

    @Test
    void theFifthFailureLocksForTheLockoutDuration() {
        UserAccount user = newUser();
        for (int i = 0; i < 4; i++) {
            user.recordFailedLogin(5, LOCKOUT, T0);
        }
        assertThat(user.isLockedAt(T0)).isFalse();

        user.recordFailedLogin(5, LOCKOUT, T0);

        assertThat(user.isLockedAt(T0)).isTrue();
        assertThat(user.isLockedAt(T0.plus(LOCKOUT).minusSeconds(1))).isTrue();
        assertThat(user.isLockedAt(T0.plus(LOCKOUT))).isFalse();
    }

    @Test
    void afterTheLockExpiresTheCountStartsAgain() {
        UserAccount user = newUser();
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(5, LOCKOUT, T0);
        }
        Instant later = T0.plus(LOCKOUT).plusSeconds(1);

        user.recordFailedLogin(5, LOCKOUT, later);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.isLockedAt(later)).isFalse();
    }

    @Test
    void aSuccessfulSignInResetsTheCount() {
        UserAccount user = newUser();
        for (int i = 0; i < 4; i++) {
            user.recordFailedLogin(5, LOCKOUT, T0);
        }

        user.recordSuccessfulLogin(T0);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(T0);
    }

    @Test
    void unlockClearsTheLockAndTheCount() {
        UserAccount user = newUser();
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(5, LOCKOUT, T0);
        }

        user.unlock();

        assertThat(user.isLockedAt(T0)).isFalse();
        assertThat(user.getFailedLoginAttempts()).isZero();
    }

    @Test
    void usernamesAreStoredLowerCase() {
        assertThat(UserAccount.create("  Alice.Nguyen ", "hash", Role.CUSTOMER).getUsername()).isEqualTo("alice.nguyen");
    }

    private static UserAccount newUser() {
        return UserAccount.create("alice", "hash", Role.CUSTOMER);
    }
}
