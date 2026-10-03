package com.payledger.security.user;

import com.payledger.security.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface UserRepository extends JpaRepository<UserAccount, UUID> {

    boolean existsByUsername(String username);

    Optional<UserAccount> findByUsername(String username);

    boolean existsByRole(Role role);

    /**
     * Sign-in attempts for one user run one at a time. Without the lock, 50 parallel guesses would all read
     * "0 failed attempts" before any of them is counted, and all 50 would be checked: a lockout bypass.
     */
    @Query(value = "SELECT * FROM users WHERE username = :username FOR NO KEY UPDATE", nativeQuery = true)
    Optional<UserAccount> findByUsernameForUpdate(String username);
}
