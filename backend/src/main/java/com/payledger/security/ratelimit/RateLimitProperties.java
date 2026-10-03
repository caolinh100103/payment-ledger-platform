package com.payledger.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code payledger.rate-limit.*}, see {@code application.yml}.
 *
 * @param redisTimeout     how long one Redis command may take before the limiter gives up and lets the request in
 * @param failOpenCooldown after a Redis failure, how long to skip rate limiting rather than wait for every timeout
 */
@ConfigurationProperties("payledger.rate-limit")
public record RateLimitProperties(boolean enabled, Duration redisTimeout, Duration failOpenCooldown,
                                  Policies policies) {

    /**
     * @param user      per signed-in user
     * @param apiKey    per machine client
     * @param anonymous per client IP address, for requests without credentials (sign-in, sign-up, refresh)
     */
    public record Policies(Policy user, Policy apiKey, Policy anonymous) {
    }

    /** A token bucket: up to {@code capacity} requests at once, refilled at {@code capacity} per {@code period}. */
    public record Policy(long capacity, Duration period) {
    }
}
