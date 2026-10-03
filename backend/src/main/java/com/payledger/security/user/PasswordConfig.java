package com.payledger.security.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
class PasswordConfig {

    /**
     * Argon2id with the OWASP Password Storage Cheat Sheet minimum: 19 MiB of memory, 2 iterations, parallelism 1,
     * a 16-byte random salt and a 32-byte hash. Memory-hard, so GPU and ASIC guessing gets expensive. Unlike bcrypt,
     * it does not silently ignore everything after the 72nd byte of a long passphrase.
     *
     * <p>Hashes carry an {@code {argon2}} prefix. If the algorithm or its cost is raised later, existing users are
     * re-hashed at their next sign-in (see {@link LoginService}).
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        Argon2PasswordEncoder argon2id = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
        return new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2id));
    }
}
