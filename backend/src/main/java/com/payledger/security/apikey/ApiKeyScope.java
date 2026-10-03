package com.payledger.security.apikey;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * What a machine client may do. A key gets only the scopes its integration needs, so a leaked top-up key cannot read
 * customer data. Scopes become {@code SCOPE_*} authorities, the Spring Security convention for OAuth scopes.
 */
public enum ApiKeyScope {

    /** Credit customers with money that has arrived at the partner bank (the top-up webhook). */
    DEPOSITS_WRITE("deposits:write");

    private final String value;

    ApiKeyScope(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public String authority() {
        return "SCOPE_" + value;
    }

    @JsonCreator
    public static ApiKeyScope of(String value) {
        return Arrays.stream(values()).filter(scope -> scope.value.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scope '" + value + "'"));
    }
}
