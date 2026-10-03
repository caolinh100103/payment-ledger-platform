package com.payledger.security.user;

import com.payledger.security.SecurityEvents;
import com.payledger.security.SecurityEvents.UserData;
import com.payledger.security.SecurityProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks a username and password, with the lockout Vietnamese banks apply to digital banking (VCB Digibank, for
 * example): after 5 wrong passwords in a row the user is locked out. Here the lock lasts 15 minutes, or until an
 * operator unlocks the user. It is not permanent, because anyone who knows a username can trigger it.
 */
@Service
public class LoginService {

    public sealed interface Outcome permits Succeeded, Rejected, Locked {
    }

    public record Succeeded(UserAccount user) implements Outcome {
    }

    /** Wrong password or unknown username. The two are deliberately indistinguishable. */
    public record Rejected() implements Outcome {
    }

    public record Locked(Instant until) implements Outcome {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SecurityEvents events;
    private final int maxFailedAttempts;
    private final Duration lockoutDuration;
    private final String dummyHash;

    LoginService(UserRepository users, PasswordEncoder passwordEncoder, SecurityEvents events,
                 SecurityProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.maxFailedAttempts = properties.login().maxFailedAttempts();
        this.lockoutDuration = properties.login().lockoutDuration();
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Never throws for bad credentials: a failed attempt must be committed to count towards the lockout, so the
     * outcome is returned and the caller turns it into a 401 after the commit.
     *
     * <p>The user row stays locked while the password is checked, so concurrent attempts are counted one by one.
     * Every attempt on an existing user is recorded for the audit trail, in the same transaction as the count.
     */
    @Transactional
    public Outcome login(String username, String password, String clientIp) {
        Optional<UserAccount> found = users.findByUsernameForUpdate(UserAccount.normalize(username));
        if (found.isEmpty()) {
            // The same work as a wrong password, so the response time does not reveal which usernames exist
            // (Spring Security's DaoAuthenticationProvider does the same).
            passwordEncoder.matches(password, dummyHash);
            return new Rejected();
        }
        UserAccount user = found.get();
        Instant now = Instant.now();
        if (user.isLockedAt(now)) {
            // Not even checked: guesses made during a lockout teach an attacker nothing.
            failed(user, clientIp, "LOCKED_OUT");
            return new Locked(user.getLockedUntil());
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.recordFailedLogin(maxFailedAttempts, lockoutDuration, now);
            failed(user, clientIp, "WRONG_PASSWORD");
            return user.isLockedAt(now) ? new Locked(user.getLockedUntil()) : new Rejected();
        }
        user.recordSuccessfulLogin(now);
        events.user(SecurityEvents.SIGNED_IN, new UserData(user.getId(), user.getUsername(), user.getRole(), clientIp,
                null, null, null, null), "user:" + user.getId());
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            // The only moment the plain password is available: re-hash with the current algorithm and cost.
            user.changePasswordHash(passwordEncoder.encode(password));
        }
        return new Succeeded(user);
    }

    // Nobody is signed in yet, so the actor is anonymous; the data says which user was targeted and from where.
    private void failed(UserAccount user, String clientIp, String reason) {
        events.user(SecurityEvents.SIGN_IN_FAILED, new UserData(user.getId(), user.getUsername(), user.getRole(),
                clientIp, user.getFailedLoginAttempts(), user.getLockedUntil(), null, reason), "anonymous");
    }
}
