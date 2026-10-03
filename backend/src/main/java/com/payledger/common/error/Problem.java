package com.payledger.common.error;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * An error as this API sends it: RFC 9457 problem details plus a stable {@code code} to branch on. Only describes the
 * OpenAPI {@code Problem} schema; responses are built as Spring {@link org.springframework.http.ProblemDetail}s.
 */
@Schema(name = Problem.SCHEMA, description = "RFC 9457 problem details with a stable `code`")
public record Problem(
        @Schema(requiredMode = REQUIRED, example = "about:blank") String type,
        @Schema(requiredMode = REQUIRED, example = "Unprocessable Content") String title,
        @Schema(requiredMode = REQUIRED, example = "422") int status,
        @Schema(description = "For a person, not for code to parse") String detail,
        @Schema(description = "The request path") String instance,
        @Schema(requiredMode = REQUIRED, example = "INSUFFICIENT_FUNDS") String code,
        @Schema(description = "The FAILED movement recorded when a business rule rejected a deposit, transfer or "
                + "reversal") UUID transferId,
        @Schema(description = "The fields that failed validation (code INVALID_REQUEST), as in RFC 9457's example")
        List<InvalidParam> invalidParams) {

    public static final String SCHEMA = "Problem";

    public record InvalidParam(String name, String reason) {
    }
}
