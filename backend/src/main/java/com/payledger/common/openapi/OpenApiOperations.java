package com.payledger.common.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * Fills in what springdoc cannot infer from a handler method:
 * <ul>
 *   <li>a stable {@code operationId}, {@code <controller>_<method>} (e.g. {@code transfer_create}), instead of
 *       numbered suffixes ({@code get_1}) that shift whenever an endpoint is added;</li>
 *   <li>the credentials the operation takes and who may call it, read from its {@code @PreAuthorize} rule, the same
 *       rule that is enforced, so the document cannot drift from it.</li>
 * </ul>
 */
@Component
class OpenApiOperations implements OperationCustomizer {

    static final String BEARER = "bearerAuth";
    static final String API_KEY = "apiKey";

    private static final String PUBLIC = "permitAll()";

    @Override
    public Operation customize(Operation operation, HandlerMethod handler) {
        String controller = handler.getBeanType().getSimpleName().replaceFirst("Controller$", "");
        operation.setOperationId(StringUtils.uncapitalize(controller) + "_" + handler.getMethod().getName());

        PreAuthorize rule = accessRule(handler);
        if (rule == null || PUBLIC.equals(rule.value())) {
            return operation;
        }
        // Scopes belong to API keys (machine clients); roles to people signed in with an access token.
        operation.addSecurityItem(new SecurityRequirement().addList(rule.value().contains("SCOPE_") ? API_KEY : BEARER));
        String access = "**Access:** `" + rule.value() + "`";
        operation.setDescription(operation.getDescription() == null ? access : operation.getDescription() + "\n\n" + access);
        return operation;
    }

    private static PreAuthorize accessRule(HandlerMethod handler) {
        PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
    }
}
