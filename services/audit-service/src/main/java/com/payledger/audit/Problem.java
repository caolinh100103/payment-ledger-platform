package com.payledger.audit;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** An error as this service sends it: RFC 9457 problem details plus a stable {@code code}, as in the core. */
@Schema(name = Problem.SCHEMA, description = "RFC 9457 problem details with a stable `code`")
record Problem(
        @Schema(requiredMode = REQUIRED, example = "about:blank") String type,
        @Schema(requiredMode = REQUIRED, example = "Forbidden") String title,
        @Schema(requiredMode = REQUIRED, example = "403") int status,
        @Schema(description = "For a person, not for code to parse") String detail,
        @Schema(description = "The request path") String instance,
        @Schema(requiredMode = REQUIRED, example = "ACCESS_DENIED") String code,
        @Schema(description = "The parameters that failed validation (code INVALID_REQUEST)")
        List<InvalidParam> invalidParams) {

    static final String SCHEMA = "Problem";

    record InvalidParam(String name, String reason) {
    }
}
