package com.payledger.security.user;

import com.payledger.common.error.BusinessRuleViolationException;
import com.payledger.security.Actor;
import com.payledger.security.Role;
import com.payledger.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN from the environment, like Keycloak's {@code KC_BOOTSTRAP_ADMIN_USERNAME} and
 * {@code KC_BOOTSTRAP_ADMIN_PASSWORD}. Only when no ADMIN exists yet and a password is configured, so the
 * variables do nothing once the platform is set up, and no default password ships with the code.
 */
@Component
class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserService userService;
    private final SecurityProperties.BootstrapAdmin properties;

    BootstrapAdmin(UserService userService, SecurityProperties properties) {
        this.userService = userService;
        this.properties = properties.bootstrapAdmin();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.password() == null || properties.password().isBlank() || userService.adminExists()) {
            return;
        }
        try {
            userService.create(properties.username(), properties.password(), Role.ADMIN, Actor.SYSTEM);
            log.info("Created bootstrap admin '{}'", properties.username());
        } catch (BusinessRuleViolationException e) {
            // Another instance won the race, or the username is used by a non-admin; either way nothing to do.
            log.warn("Bootstrap admin not created: {}", e.getMessage());
        }
    }
}
