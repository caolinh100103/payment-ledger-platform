package com.payledger.security.user;

import com.payledger.common.error.BusinessRuleViolationException;
import com.payledger.common.error.ResourceNotFoundException;
import com.payledger.security.Actor;
import com.payledger.security.Role;
import com.payledger.security.SecurityEvents;
import com.payledger.security.SecurityEvents.UserData;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SecurityEvents events;
    private final TransactionTemplate transaction;

    UserService(UserRepository users, PasswordEncoder passwordEncoder, SecurityEvents events,
                PlatformTransactionManager transactionManager) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Hashing takes tens of milliseconds and needs no database connection, so only the insert and its audit event
     * run in a transaction. The unique constraint on {@code username}, not the {@code exists} check, is what stops
     * two concurrent sign-ups.
     *
     * @param createdBy the user themselves when signing up (anonymous), or the ADMIN who created them
     */
    public UserAccount create(String username, String password, Role role, Actor createdBy) {
        PasswordPolicy.check(username, password);
        String normalized = UserAccount.normalize(username);
        if (users.existsByUsername(normalized)) {
            throw usernameTaken(normalized);
        }
        UserAccount user = UserAccount.create(normalized, passwordEncoder.encode(password), role);
        try {
            return transaction.execute(status -> {
                UserAccount saved = users.saveAndFlush(user);
                events.user(SecurityEvents.USER_CREATED, UserData.of(saved.getId(), saved.getUsername(),
                        saved.getRole()), createdBy.name());
                return saved;
            });
        } catch (DataIntegrityViolationException e) {
            throw usernameTaken(normalized);
        }
    }

    @Transactional(readOnly = true)
    public UserAccount get(UUID id) {
        return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    /** Exact match on the normalized username; no partial search, so the user list cannot be walked. */
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByUsername(String username) {
        return users.findByUsername(UserAccount.normalize(username));
    }

    @Transactional
    public UserAccount unlock(UUID id, Actor operator) {
        UserAccount user = get(id);
        user.unlock();
        UserAccount saved = users.saveAndFlush(user);
        events.user(SecurityEvents.UNLOCKED, UserData.of(saved.getId(), saved.getUsername(), saved.getRole()),
                operator.name());
        return saved;
    }

    boolean adminExists() {
        return users.existsByRole(Role.ADMIN);
    }

    private static BusinessRuleViolationException usernameTaken(String username) {
        return new BusinessRuleViolationException("USERNAME_TAKEN", "Username " + username + " is already taken");
    }
}
