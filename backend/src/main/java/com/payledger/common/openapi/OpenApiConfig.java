package com.payledger.common.openapi;

import com.payledger.common.error.Problem;
import com.payledger.security.Actor;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The OpenAPI document of the API, generated from the controllers by springdoc and served at {@code /v3/api-docs}.
 * It is the contract the web app is typed against: {@code frontend/openapi/core.json} is a copy of it, kept current by
 * {@code OpenApiContractTest}, and the frontend's TypeScript types are generated from that copy (ADR 0016).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String PROBLEM_MEDIA_TYPE = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    static {
        // Resolved from the access token, not sent by the client.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Actor.class);
    }

    @Bean
    OpenAPI payledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayLedger Core API")
                        .version("v1")
                        .description("""
                                Accounts, deposits, transfers and reversals on a double-entry ledger, and the \
                                sign-in, user and API key management that guards them. Amounts are integers in the \
                                currency's minor unit. Errors are RFC 9457 problem details with a stable `code`."""))
                // Relative, so the document is the same wherever the API runs, and byte-for-byte reproducible in tests.
                .servers(List.of(new Server().url("/")))
                .components(new Components()
                        .addSecuritySchemes(OpenApiOperations.BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("An ES256 access token (RFC 9068) from /api/v1/auth/login"))
                        .addSecuritySchemes(OpenApiOperations.API_KEY, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-API-Key")
                                .description("A machine client's key, e.g. the partner bank's, with its scopes")));
    }

    /**
     * Two facts springdoc cannot see:
     * <ul>
     *   <li>Every error is a {@link Problem}: it becomes the {@code default} response of every operation.</li>
     *   <li>Every property of a response is always present, because Jackson writes nulls too. Marked required, so a
     *       generated client does not have to treat each one as possibly missing; a property that can be null says so
     *       with {@code @Schema(nullable = true)}. Request bodies keep the required list their validation gives.</li>
     * </ul>
     */
    @Bean
    OpenApiCustomizer problemsAndRequiredResponseProperties() {
        return openApi -> {
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            schemas.putAll(ModelConverters.getInstance(true).readAll(Problem.class));
            Schema<?> problem = new Schema<>().$ref("#/components/schemas/" + Problem.SCHEMA);

            Set<String> requestBodies = new HashSet<>();
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (operation.getRequestBody() != null) {
                    operation.getRequestBody().getContent().values()
                            .forEach(media -> requestBodies.add(schemaName(media.getSchema())));
                }
                operation.getResponses().addApiResponse("default", new io.swagger.v3.oas.models.responses.ApiResponse()
                        .description("An RFC 9457 problem; `code` says what went wrong")
                        .content(new Content().addMediaType(PROBLEM_MEDIA_TYPE, new MediaType().schema(problem))));
            }));

            schemas.forEach((name, schema) -> {
                if (!requestBodies.contains(name) && !Problem.SCHEMA.equals(name) && schema.getProperties() != null) {
                    schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
                }
            });
        };
    }

    private static String schemaName(Schema<?> schema) {
        return schema == null || schema.get$ref() == null ? "" : schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
    }
}
