package com.payledger.common.idempotency;

import io.swagger.v3.oas.annotations.Parameter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How the {@code Idempotency-Key} header appears in the OpenAPI document. Declared optional to Spring MVC so that
 * {@link IdempotencyHandler} can answer its absence with its own problem code, but required all the same.
 */
@Parameter(required = true, example = "6f1c2a9e-0d1b-4c55-9b3e-7a2f8e4d1c00", description = """
        A new unique value (e.g. a UUID) per operation. A retry with the same key and body returns the original \
        outcome instead of moving money twice; with a different body, 422 IDEMPOTENCY_KEY_REUSED.""")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface IdempotencyKey {
}
