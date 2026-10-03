package com.payledger.security;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may do what (the role table in the README), and object-level access: OWASP API Security Top 10 API1 (Broken
 * Object Level Authorization) and API5 (Broken Function Level Authorization).
 */
class AccessControlIntegrationTest extends ApiTestSupport {

    // ---------------------------------------------------------------------------------------------------------------
    // Object level: customers only reach their own accounts
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void aCustomerCannotSeeSomeoneElsesAccount() {
        String victim = fundedAccount("VND", 1_000_000);

        MvcTestResult result = mvc.get().uri("/api/v1/accounts/{id}", victim).with(asCustomer(newCustomer()))
                .exchange();

        // Same answer as for an account that does not exist, so ids cannot be probed.
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void aCustomerCannotListSomeoneElsesAccounts() {
        String victim = ownerOf(openAccount("VND"));

        assertThat(mvc.get().uri("/api/v1/accounts").param("ownerId", victim).with(asCustomer(newCustomer())))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("ACCESS_DENIED");
    }

    @Test
    void aCustomerCannotSpendFromSomeoneElsesAccountAndLeavesNoTraceTrying() {
        String victim = fundedAccount("VND", 1_000_000);
        String thief = openAccount("VND");
        long transfersBefore = jdbc.queryForObject("SELECT count(*) FROM transfers", Long.class);

        MvcTestResult attempt = postWithKey("/api/v1/transfers", UUID.randomUUID().toString(),
                transferBody(victim, thief, 1_000_000, "VND"), asOwnerOf(thief));

        assertThat(attempt).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(balanceOf(victim)).isEqualTo(1_000_000);
        // Rejected before anything was locked or recorded: nothing appears in the victim's history.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Long.class)).isEqualTo(transfersBefore);
    }

    @Test
    void aCustomerCanPayIntoAnyCustomersAccount() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");

        assertThat(transfer(alice, bob, 10_000, "VND")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void bothPartiesSeeATransferAndNobodyElseDoes() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String transferId = jsonPath(transfer(alice, bob, 10_000, "VND"), "$.id");

        assertThat(mvc.get().uri("/api/v1/transfers/{id}", transferId).with(asOwnerOf(alice))).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/transfers/{id}", transferId).with(asOwnerOf(bob))).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/transfers/{id}", transferId).with(asOperator())).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/transfers/{id}", transferId).with(asCustomer(newCustomer())))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCustomerCannotCloseSomeoneElsesAccount() {
        String victim = openAccount("VND");

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/close", victim).with(asCustomer(newCustomer())))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/v1/accounts/{id}", victim).with(asOwnerOf(victim)))
                .bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
    }

    @Test
    void theTransferRecordsWhoInitiatedIt() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");

        String transferId = jsonPath(transfer(alice, bob, 10_000, "VND"), "$.id");

        assertThat(jdbc.queryForObject("SELECT initiated_by FROM transfers WHERE id = ?::uuid", String.class,
                transferId)).isEqualTo("user:" + ownerOf(alice));
        assertThat(jdbc.queryForObject("""
                SELECT initiated_by FROM transfers WHERE destination_account_id = ?::uuid AND type = 'DEPOSIT'
                """, String.class, alice)).startsWith("apikey:");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Function level: roles
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void onlyAnOperatorFreezesAccountsAndReversesTransfers() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String transferId = jsonPath(transfer(alice, bob, 10_000, "VND"), "$.id");

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/freeze", alice).with(asOwnerOf(alice)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/api/v1/accounts/{id}/freeze", alice).with(asAuditor()))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(reverse(transferId, asOwnerOf(bob))).hasStatus(HttpStatus.FORBIDDEN);

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/freeze", bob).with(asOperator())).hasStatusOk();
        assertThat(reverse(transferId, asOperator())).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void anAdminHasTheOperatorsPowers() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String transferId = jsonPath(transfer(alice, bob, 10_000, "VND"), "$.id");

        assertThat(mvc.post().uri("/api/v1/accounts/{id}/freeze", bob).with(asAdmin())).hasStatusOk();
        assertThat(reverse(transferId, asAdmin())).hasStatus(HttpStatus.CREATED);
    }

    /** Segregation of duties: managing the platform does not include spending customers' money. */
    @Test
    void neitherAnAdminNorAnOperatorCanMoveACustomersMoney() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String body = transferBody(alice, bob, 10_000, "VND");

        assertThat(postWithKey("/api/v1/transfers", UUID.randomUUID().toString(), body, asAdmin()))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(postWithKey("/api/v1/transfers", UUID.randomUUID().toString(), body, asOperator()))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(balanceOf(alice)).isEqualTo(100_000);
    }

    /** A deposit creates customer money; only the bank, which saw the money arrive, may report one. */
    @Test
    void onlyTheBankIntegrationCanDeposit() {
        String account = openAccount("VND");
        String body = """
                {"accountId": "%s", "amount": 1000000, "currency": "VND"}
                """.formatted(account);

        assertThat(postWithKey("/api/v1/deposits", UUID.randomUUID().toString(), body, asOwnerOf(account)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(postWithKey("/api/v1/deposits", UUID.randomUUID().toString(), body, asAdmin()))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(balanceOf(account)).isZero();

        assertThat(postWithKey("/api/v1/deposits", UUID.randomUUID().toString(), body, asBank()))
                .hasStatus(HttpStatus.CREATED);
    }

    /** The bank's key can report deposits and nothing else. */
    @Test
    void theBankKeyCannotReadOrMoveCustomerMoney() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");

        assertThat(mvc.get().uri("/api/v1/accounts/{id}", alice).with(asBank())).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(postWithKey("/api/v1/transfers", UUID.randomUUID().toString(),
                transferBody(alice, bob, 1_000, "VND"), asBank()))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAuditorCannotReadAccounts() {
        assertThat(mvc.get().uri("/api/v1/accounts/{id}", openAccount("VND")).with(asAuditor()))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void anonymousCallersGetNothing() {
        String account = openAccount("VND");

        assertThat(mvc.get().uri("/api/v1/accounts/{id}", account)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\": \"VND\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void actuatorEndpointsBeyondProbesAndMetricsAreForAdmins() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics").with(asOperator())).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/actuator/metrics").with(asAdmin())).hasStatusOk();
    }

    private MvcTestResult reverse(String transferId, RequestPostProcessor caller) {
        return postWithKey("/api/v1/transfers/" + transferId + "/reversals", UUID.randomUUID().toString(), """
                {"reason": "Customer dispute"}
                """, caller);
    }
}
