package com.payledger.security.apikey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates requests that carry an {@code X-API-Key} header. A header that is present but not a valid key is
 * answered with 401 right away, rather than silently treating the request as anonymous.
 *
 * <p>Not a Spring bean: Spring Boot would register a bean filter a second time, outside the security chain.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeys apiKeys;
    private final AuthenticationEntryPoint entryPoint;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public ApiKeyAuthenticationFilter(ApiKeys apiKeys, AuthenticationEntryPoint entryPoint) {
        this.apiKeys = apiKeys;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<ApiKeyAuthentication> authentication = apiKeys.authenticate(key.strip());
        if (authentication.isEmpty()) {
            contextHolder.clearContext();
            entryPoint.commence(request, response, new InvalidApiKeyException());
            return;
        }
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication.get());
        contextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    /** The {@code X-API-Key} header holds no valid key. */
    public static class InvalidApiKeyException extends AuthenticationException {

        InvalidApiKeyException() {
            super("Invalid API key");
        }
    }
}
