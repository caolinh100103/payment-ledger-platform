package com.payledger.support;

import com.payledger.security.Role;
import com.payledger.security.token.AccessTokens;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Signs every MockMvc request in with a fresh access token, unless the request sets its own Authorization header.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DefaultAccessToken {

    @Bean
    MockMvcBuilderCustomizer defaultAccessToken(AccessTokens accessTokens) {
        UUID user = UUID.randomUUID();
        return builder -> builder.defaultRequest(get("/").with(request -> {
            if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
                String token = accessTokens.issue(user, Role.OPERATOR, UUID.randomUUID()).value();
                request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request;
        }));
    }
}
