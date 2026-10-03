package com.payledger.common.error;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring MVC's own errors carry a {@code code} like every other problem, so a client never branches on status alone. */
class ProblemDetailsIntegrationTest extends ApiTestSupport {

    @Test
    void aValidationFailureListsTheInvalidFields() {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts")
                .with(asCustomer(newCustomer()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currency": "vnd"}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(result).bodyJson().extractingPath("$.invalidParams[0].name").isEqualTo("currency");
        assertThat(result).bodyJson().extractingPath("$.invalidParams[0].reason")
                .isEqualTo("must be an ISO 4217 code, e.g. VND");
    }

    @Test
    void malformedJsonIsAnInvalidRequest() {
        assertThat(mvc.post().uri("/api/v1/accounts")
                .with(asCustomer(newCustomer()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\": "))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
    }

    @Test
    void anUnsupportedMethodIsNamed() {
        assertThat(mvc.delete().uri("/api/v1/accounts").with(asCustomer(newCustomer())))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .bodyJson().extractingPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }
}
