package com.payledger.audit;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * The OpenAPI document of this service at {@code /v3/api-docs}. The web app is typed against a copy of it,
 * {@code frontend/openapi/audit.json}, which {@code OpenApiContractTest} keeps current (ADR 0016).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI auditOpenApi() {
        return new OpenAPI()
                .info(new Info().title("PayLedger Audit API").version("v1").description("The tamper-evident audit trail: every event of the platform, hash-chained. Read-only, for auditors."))
                // Relative, so the document is the same wherever the service runs.
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("An access token issued by the core service, verified with its JWKS")))
                // Every endpoint takes the core's access token.
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /**
     * As in the core: every error is a {@link Problem} (the {@code default} response of every operation), and every
     * response property is always present, so it is marked required.
     */
    @Bean
    OpenApiCustomizer problemsAndRequiredResponseProperties() {
        return openApi -> {
            openApi.getComponents().getSchemas().putAll(ModelConverters.getInstance(true).readAll(Problem.class));
            Schema<?> problem = new Schema<>().$ref("#/components/schemas/" + Problem.SCHEMA);
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                    operation.getResponses().addApiResponse("default", new ApiResponse()
                            .description("An RFC 9457 problem; `code` says what went wrong")
                            .content(new Content().addMediaType(
                                    org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                    new MediaType().schema(problem))))));
            // This service takes no request bodies: every schema but the problem is a response.
            openApi.getComponents().getSchemas().forEach((name, schema) -> {
                if (!Problem.SCHEMA.equals(name) && schema.getProperties() != null) {
                    schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
                }
            });
        };
    }
}
