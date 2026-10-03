package com.payledger.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code payledger.security.*}, see {@code application.yml} for the defaults and their reasons.
 *
 * @param bootstrapAdmin the first ADMIN, created at startup when no ADMIN exists and a password is set
 */
@ConfigurationProperties("payledger.security")
public record SecurityProperties(Jwt jwt, Refresh refresh, Login login, BootstrapAdmin bootstrapAdmin) {

    /**
     * @param signingKey the private EC P-256 key as a JWK (JSON); blank means a random key per start
     */
    public record Jwt(String issuer, String audience, String clientId, Duration accessTokenTtl, String signingKey) {
    }

    /**
     * @param tokenTtl   idle timeout: how long one refresh token stays valid
     * @param sessionTtl absolute lifetime of a sign-in session
     */
    public record Refresh(Duration tokenTtl, Duration sessionTtl) {
    }

    public record Login(int maxFailedAttempts, Duration lockoutDuration) {
    }

    public record BootstrapAdmin(String username, String password) {
    }
}
