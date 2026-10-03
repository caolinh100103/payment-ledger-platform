package com.payledger.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the Spring Security authentication of the current thread into an {@link Actor}, and resolves
 * {@code Actor} controller method arguments.
 */
@Component
public class CurrentActor implements HandlerMethodArgumentResolver {

    private final RoleHierarchy roleHierarchy;

    CurrentActor(RoleHierarchy roleHierarchy) {
        this.roleHierarchy = roleHierarchy;
    }

    public Actor get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return switch (authentication) {
            case null -> Actor.SYSTEM;
            case AnonymousAuthenticationToken anonymous -> Actor.ANONYMOUS;
            case JwtAuthenticationToken jwt -> new Actor(Actor.Kind.USER, jwt.getName(), authoritiesOf(jwt));
            default -> throw new IllegalStateException("Unsupported authentication " + authentication.getClass());
        };
    }

    private Set<String> authoritiesOf(Authentication authentication) {
        return roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Actor.class;
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                 NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return get();
    }
}
