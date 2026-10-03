package com.payledger.security.ratelimit;

import com.payledger.security.Actor;
import com.payledger.security.CurrentActor;
import com.payledger.security.ProblemWriter;
import com.payledger.security.ratelimit.RateLimitProperties.Policies;
import com.payledger.security.ratelimit.RateLimitProperties.Policy;
import com.payledger.security.ratelimit.RateLimiter.Allowed;
import com.payledger.security.ratelimit.RateLimiter.Bypassed;
import com.payledger.security.ratelimit.RateLimiter.Rejected;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Limits API requests per caller: per user for an access token, per key for an API key, and per IP address for
 * requests without credentials, which is what slows down credential stuffing against sign-in. Runs after
 * authentication, so it knows who is calling, and before authorization.
 *
 * <p>Every limited response carries GitHub-style {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining}; a
 * rejected one is 429 with {@code Retry-After} (RFC 6585, RFC 9110). Probes, metrics and the JWKS are not limited.
 *
 * <p>Not a Spring bean: Spring Boot would register a bean filter a second time, outside the security chain.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String LIMIT_HEADER = "X-RateLimit-Limit";
    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final RateLimiter limiter;
    private final Policies policies;
    private final CurrentActor currentActor;
    private final ProblemWriter problems;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties, CurrentActor currentActor,
                           ProblemWriter problems) {
        this.limiter = limiter;
        this.policies = properties.policies();
        this.currentActor = currentActor;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Actor actor = currentActor.get();
        // Behind a reverse proxy, set server.forward-headers-strategy so the remote address is the client's.
        String key;
        String policyName;
        Policy policy;
        switch (actor.kind()) {
            case USER -> {
                key = actor.name();
                policyName = "user";
                policy = policies.user();
            }
            case API_KEY -> {
                key = actor.name();
                policyName = "api-key";
                policy = policies.apiKey();
            }
            default -> {
                key = "ip:" + request.getRemoteAddr();
                policyName = "anonymous";
                policy = policies.anonymous();
            }
        }

        switch (limiter.tryConsume(key, policyName, policy)) {
            case Allowed allowed -> {
                response.setHeader(LIMIT_HEADER, Long.toString(allowed.limit()));
                response.setHeader(REMAINING_HEADER, Long.toString(allowed.remaining()));
                chain.doFilter(request, response);
            }
            case Rejected rejected -> {
                response.setHeader(LIMIT_HEADER, Long.toString(rejected.limit()));
                response.setHeader(REMAINING_HEADER, "0");
                // Whole seconds, rounded up, so a client that waits exactly this long gets a token.
                long seconds = Math.max(1, (rejected.retryAfter().toMillis() + 999) / 1000);
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
                problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many requests; retry after " + seconds + " s");
            }
            case Bypassed bypassed -> chain.doFilter(request, response);
        }
    }
}
