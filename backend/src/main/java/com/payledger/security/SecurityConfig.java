package com.payledger.security;

import com.payledger.security.token.AccessTokens;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Stateless API security: every request carries its own credentials (a bearer access token), the server keeps no
 * HTTP session, and no cookie is ever read.
 *
 * <p>Two layers decide access. The filter chain below only separates public endpoints from the rest. The
 * {@code @PreAuthorize} rule on each controller method decides who may call it, next to the code it protects;
 * {@code EndpointSecurityTest} fails the build if a handler method has none.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig implements WebMvcConfigurer {

    private final CurrentActor currentActor;
    private final SecurityProperties properties;

    SecurityConfig(CurrentActor currentActor, SecurityProperties properties) {
        this.currentActor = currentActor;
        this.properties = properties;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
        http
                // CSRF forges requests that ride on credentials the browser attaches by itself (cookies). A bearer
                // token is only sent when the client's code adds it, so there is nothing to forge.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        // Probes and Prometheus scraping; in production the management port is not exposed publicly.
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
                        // Error dispatches render the original status (e.g. 404) instead of a misleading 401.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        // RFC 9728: /.well-known/oauth-protected-resource tells a client which issuer's tokens
                        // this API accepts; every 401 points to it in WWW-Authenticate.
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                                builder -> builder.resourceName("PayLedger API")
                                        .authorizationServer(properties.jwt().issuer())))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    /**
     * ADMIN inherits the back-office roles. Deliberately not CUSTOMER: moving a customer's money takes the
     * customer's own credentials, so an administrator cannot pay themselves from someone else's account.
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.ADMIN.name()).implies(Role.OPERATOR.name(), Role.AUDITOR.name())
                .build();
    }

    /** The {@code roles} claim becomes {@code ROLE_*} authorities; the principal name is the subject (user id). */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(AccessTokens.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentActor);
    }
}
