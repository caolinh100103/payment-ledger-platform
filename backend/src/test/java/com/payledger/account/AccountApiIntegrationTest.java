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
    void opensAnAccountForTheSignedInCustomerAndReadsItBack() {
        String customer = newCustomer();
        MvcTestResult created = openAccountRequest(customer, "VND");

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).headers().containsHeader("Location");
        assertThat(created).bodyJson().extractingPath("$.ownerId").isEqualTo(customer);
        assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
        assertThat(created).bodyJson().extractingPath("$.type").isEqualTo("CUSTOMER");
        assertThat(created).bodyJson().extractingPath("$.balance").isEqualTo(0);

        String id = idOf(created);
        assertThat(mvc.get().uri("/api/v1/accounts/{id}", id).with(asCustomer(customer)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.ownerId").isEqualTo(customer);
    }

    @Test
    void theOwnerComesFromTheAccessTokenNotTheRequest() {
        String customer = newCustomer();

        MvcTestResult created = mvc.post().uri("/api/v1/accounts")
                .with(asCustomer(customer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ownerId": "%s", "currency": "VND"}
                        """.formatted(newCustomer()))
                .exchange();

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.ownerId").isEqualTo(customer);
    }

    @Test
    void listsTheCallersOwnAccounts() {
        String customer = newCustomer();
        openAccountRequest(customer, "VND");
        openAccountRequest(customer, "USD");
        openAccount("VND");

        assertThat(mvc.get().uri("/api/v1/accounts").with(asCustomer(customer)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.length()").isEqualTo(2);
    }

    @Test
    void anOperatorListsAnyCustomersAccounts() {
        String customer = newCustomer();
        openAccountRequest(customer, "VND");
        openAccountRequest(customer, "USD");

        assertThat(mvc.get().uri("/api/v1/accounts").param("ownerId", customer).with(asOperator()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.length()").isEqualTo(2);
    }

    @Test
    void rejectsInvalidRequestWithProblemDetail() {
        assertThat(mvc.post().uri("/api/v1/accounts")
                .with(asCustomer(newCustomer()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currency": "vnd"}
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThat(openAccountRequest(newCustomer(), "JPY"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void returnsNotFoundForUnknownAccount() {
        assertThat(mvc.get().uri("/api/v1/accounts/{id}", UUID.randomUUID()).with(asOperator()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void walksThroughStatusLifecycle() {
        String customer = newCustomer();
        String id = idOf(openAccountRequest(customer, "EUR"));

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/freeze", id).with(asOperator()))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("FROZEN");

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/freeze", id).with(asOperator()))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_ACCOUNT_STATUS_TRANSITION");

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/unfreeze", id).with(asOperator()))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");

        // The customer closes their own (empty) account.
        assertThat(mvc.post().uri("/api/v1/accounts/{id}/close", id).with(asCustomer(customer)))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("CLOSED");
    }

    @Test
    void exposesPrometheusMetricsWithoutAuthentication() {
        assertThat(mvc.get().uri("/actuator/prometheus"))
                .hasStatusOk()
                .bodyText().contains("jvm_memory_used_bytes");
    }

    private MvcTestResult openAccountRequest(String ownerId, String currency) {
        return mvc.post().uri("/api/v1/accounts")
                .with(asCustomer(ownerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currency": "%s"}
                        """.formatted(currency))
                .exchange();
    }

    private static String idOf(MvcTestResult result) {
        return jsonPath(result, "$.id");
    }
}
