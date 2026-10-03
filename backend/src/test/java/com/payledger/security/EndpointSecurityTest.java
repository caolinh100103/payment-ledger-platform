package com.payledger.security;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deny by default: every endpoint must state who may call it, next to its code. A new controller method without
 * {@code @PreAuthorize} would otherwise be open to anyone holding any token, so it fails the build instead.
 */
class EndpointSecurityTest extends ApiTestSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyEndpointDeclaresItsAccessRule() {
        List<HandlerMethod> endpoints = handlerMapping.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().getPackageName().startsWith("com.payledger"))
                .toList();

        assertThat(endpoints).hasSizeGreaterThan(20);
        assertThat(endpoints)
                .filteredOn(handler -> !AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
                        && !AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class))
                .extracting(HandlerMethod::getShortLogMessage)
                .as("endpoints without @PreAuthorize")
                .isEmpty();
    }
}
