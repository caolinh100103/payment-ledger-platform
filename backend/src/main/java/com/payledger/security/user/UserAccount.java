package com.payledger.security.user;

import com.payledger.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/**
 * A person who signs in. The lockout rules live here, and take {@code now} as an argument so they can be tested
 * without waiting for a lock to expire.
 */
@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
        // for JPA
    }

    static UserAccount create(String username, String passwordHash, Role role) {
        UserAccount user = new UserAccount();
        user.id = UUID.randomUUID();
        user.username = normalize(username);
        user.passwordHash = passwordHash;
        user.role = role;
        user.createdAt = now();
        user.updatedAt = user.createdAt;
        return user;
    }

    /** Usernames are case-insensitive: "Alice" signs in as "alice". */
    static String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Counts a wrong password; the attempt that reaches {@code maxAttempts} locks the user out. */
    void recordFailedLogin(int maxAttempts, Duration lockout, Instant now) {
        clearExpiredLock(now);
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockout);
        }
    }

    void recordSuccessfulLogin(Instant now) {
        clearExpiredLock(now);
        failedLoginAttempts = 0;
        lastLoginAt = now;
    }

    /** An operator lifts the lock early, e.g. after the customer called the hotline and was identified. */
    void unlock() {
        failedLoginAttempts = 0;
        lockedUntil = null;
    }

    void changePasswordHash(String newHash) {
        passwordHash = newHash;
    }

    // A lock lapses on its own; the next attempt then starts counting from zero again.
    private void clearExpiredLock(Instant now) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            lockedUntil = null;
            failedLoginAttempts = 0;
        }
    }

    @PreUpdate
    void touch() {
        updatedAt = now();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
