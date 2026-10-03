package com.payledger.security.user;

import com.payledger.common.error.BusinessRuleViolationException;
import com.payledger.common.error.ResourceNotFoundException;
import com.payledger.security.Role;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Not transactional on purpose: hashing takes tens of milliseconds and needs no database connection. The unique
     * constraint on {@code username}, not the {@code exists} check, is what stops two concurrent sign-ups.
     */
    public UserAccount create(String username, String password, Role role) {
        PasswordPolicy.check(username, password);
        String normalized = UserAccount.normalize(username);
        if (users.existsByUsername(normalized)) {
            throw usernameTaken(normalized);
        }
        UserAccount user = UserAccount.create(normalized, passwordEncoder.encode(password), role);
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw usernameTaken(normalized);
        }
    }

    @Transactional(readOnly = true)
    public UserAccount get(UUID id) {
        return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Transactional
    public UserAccount unlock(UUID id) {
        UserAccount user = get(id);
        user.unlock();
        return users.saveAndFlush(user);
    }

    boolean adminExists() {
        return users.existsByRole(Role.ADMIN);
    }

    private static BusinessRuleViolationException usernameTaken(String username) {
        return new BusinessRuleViolationException("USERNAME_TAKEN", "Username " + username + " is already taken");
    }
}
