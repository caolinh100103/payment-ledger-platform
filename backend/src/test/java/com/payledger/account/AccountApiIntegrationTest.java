package com.payledger.account;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountApiIntegrationTest extends ApiTestSupport {

    @Test
    void opensAccountAndReadsItBack() {
        MvcTestResult created = openAccountRequest("alice", "VND");

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).headers().containsHeader("Location");
        assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
        assertThat(created).bodyJson().extractingPath("$.type").isEqualTo("CUSTOMER");
        assertThat(created).bodyJson().extractingPath("$.balance").isEqualTo(0);

        String id = idOf(created);
        assertThat(mvc.get().uri("/api/v1/accounts/{id}", id))
                .hasStatusOk()
                .bodyJson().extractingPath("$.ownerId").isEqualTo("alice");
    }

    @Test
    void listsAccountsByOwner() {
        String owner = "owner-" + UUID.randomUUID();
        openAccountRequest(owner, "VND");
        openAccountRequest(owner, "USD");

        assertThat(mvc.get().uri("/api/v1/accounts").param("ownerId", owner))
                .hasStatusOk()
                .bodyJson().extractingPath("$.length()").isEqualTo(2);
    }

    @Test
    void rejectsInvalidRequestWithProblemDetail() {
        assertThat(mvc.post().uri("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ownerId": "", "currency": "vnd"}
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThat(openAccountRequest("bob", "JPY"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void returnsNotFoundForUnknownAccount() {
        assertThat(mvc.get().uri("/api/v1/accounts/{id}", UUID.randomUUID()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void walksThroughStatusLifecycle() {
        String id = idOf(openAccountRequest("carol", "EUR"));

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/freeze", id))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("FROZEN");

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/freeze", id))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_ACCOUNT_STATUS_TRANSITION");

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/unfreeze", id))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/close", id))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("CLOSED");
    }

    @Test
    void exposesPrometheusMetrics() {
        assertThat(mvc.get().uri("/actuator/prometheus"))
                .hasStatusOk()
                .bodyText().contains("jvm_memory_used_bytes");
    }

    private MvcTestResult openAccountRequest(String ownerId, String currency) {
        return mvc.post().uri("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ownerId": "%s", "currency": "%s"}
                        """.formatted(ownerId, currency))
                .exchange();
    }

    private static String idOf(MvcTestResult result) {
        return jsonPath(result, "$.id");
    }
}
