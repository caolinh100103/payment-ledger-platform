package com.payledger.transfer;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountStatementApiIntegrationTest extends ApiTestSupport {

    @Test
    void listsEntriesNewestFirstWithTheMovementAndTheBalanceLeft() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        String transferId = jsonPath(transfer(alice, bob, 250_000, "VND"), "$.id");

        MvcTestResult result = statement(alice, null, 20);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.hasMore").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.data.length()").isEqualTo(2);
        // The transfer out, then the top-up before it.
        assertThat(result).bodyJson().extractingPath("$.data[0].transferId").isEqualTo(transferId);
        assertThat(result).bodyJson().extractingPath("$.data[0].type").isEqualTo("TRANSFER");
        assertThat(result).bodyJson().extractingPath("$.data[0].direction").isEqualTo("DEBIT");
        assertThat(result).bodyJson().extractingPath("$.data[0].amount").isEqualTo(250_000);
        assertThat(result).bodyJson().extractingPath("$.data[0].balanceAfter").isEqualTo(750_000);
        assertThat(result).bodyJson().extractingPath("$.data[0].counterpartyAccountId").isEqualTo(bob);
        assertThat(result).bodyJson().extractingPath("$.data[0].description").isEqualTo("Tiền nhà tháng 10");
        assertThat(result).bodyJson().extractingPath("$.data[1].type").isEqualTo("DEPOSIT");
        assertThat(result).bodyJson().extractingPath("$.data[1].direction").isEqualTo("CREDIT");
        assertThat(result).bodyJson().extractingPath("$.data[1].balanceAfter").isEqualTo(1_000_000);
        assertThat(result).bodyJson().extractingPath("$.data[1].counterpartyAccountId")
                .isEqualTo(systemAccountId("VND"));

        assertThat(statement(bob, null, 20)).bodyJson().extractingPath("$.data[0].direction").isEqualTo("CREDIT");
        assertThat(statement(bob, null, 20)).bodyJson().extractingPath("$.data[0].counterpartyAccountId")
                .isEqualTo(alice);
    }

    /** Each page continues exactly where the last one ended, even while new entries are posted in between. */
    @Test
    void pagesWithACursor() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        for (int i = 0; i < 4; i++) {
            transfer(alice, bob, 1_000, "VND");
        }

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        boolean hasMore = true;
        int pages = 0;
        while (hasMore) {
            MvcTestResult page = statement(alice, cursor, 2);
            List<Number> ids = jsonPath(page, "$.data[*].id");
            ids.forEach(id -> seen.add(id.longValue()));
            hasMore = jsonPath(page, "$.hasMore");
            cursor = seen.getLast();
            pages++;
            if (pages == 1) {
                // A newer entry posted between two pages must not shift the next one.
                transfer(alice, bob, 1_000, "VND");
            }
        }

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(5).doesNotHaveDuplicates().isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    void aReversedMovementSaysSo() {
        String alice = fundedAccount("VND", 100_000);
        String bob = openAccount("VND");
        String original = jsonPath(transfer(alice, bob, 40_000, "VND"), "$.id");
        postWithKey("/api/v1/transfers/" + original + "/reversals", UUID.randomUUID().toString(), """
                {"reason": "Sent to the wrong account"}
                """, asOperator());

        MvcTestResult result = statement(alice, null, 20);

        assertThat(result).bodyJson().extractingPath("$.data[0].type").isEqualTo("REVERSAL");
        assertThat(result).bodyJson().extractingPath("$.data[0].direction").isEqualTo("CREDIT");
        assertThat(result).bodyJson().extractingPath("$.data[0].balanceAfter").isEqualTo(100_000);
        assertThat(result).bodyJson().extractingPath("$.data[1].transferId").isEqualTo(original);
        assertThat(result).bodyJson().extractingPath("$.data[1].transferStatus").isEqualTo("REVERSED");
    }

    @Test
    void aRejectedTransferLeavesNoEntry() {
        String alice = fundedAccount("VND", 1_000);
        transfer(alice, openAccount("VND"), 5_000, "VND");

        assertThat(statement(alice, null, 20)).bodyJson().extractingPath("$.data.length()").isEqualTo(1);
    }

    @Test
    void someoneElsesStatementIsNotFoundButAnOperatorCanReadIt() {
        String victim = fundedAccount("VND", 1_000_000);

        assertThat(mvc.get().uri("/api/v1/accounts/{id}/ledger-entries", victim).with(asCustomer(newCustomer())))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(mvc.get().uri("/api/v1/accounts/{id}/ledger-entries", victim).with(asOperator()))
                .hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/accounts/{id}/ledger-entries", victim).with(asAuditor()))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void anOutOfRangeLimitNamesTheParameter() {
        String alice = openAccount("VND");

        MvcTestResult result = statement(alice, null, 1_000);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(result).bodyJson().extractingPath("$.invalidParams[0].name").isEqualTo("limit");
    }

    private MvcTestResult statement(String accountId, Long startingAfter, int limit) {
        var request = mvc.get().uri("/api/v1/accounts/{id}/ledger-entries", accountId)
                .param("limit", Integer.toString(limit))
                .with(asOwnerOf(accountId));
        if (startingAfter != null) {
            request = request.param("startingAfter", startingAfter.toString());
        }
        return request.exchange();
    }
}
