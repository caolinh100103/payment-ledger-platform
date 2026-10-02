package com.payledger.notification;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static com.payledger.notification.TestEvents.ALICE_ACCOUNT;
import static com.payledger.notification.TestEvents.BOB_ACCOUNT;
import static com.payledger.notification.TestEvents.SYSTEM_ACCOUNT;
import static com.payledger.notification.TestEvents.account;
import static com.payledger.notification.TestEvents.data;
import static com.payledger.notification.TestEvents.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class BalanceChangeMessagesTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void transferNotifiesPayerAndPayeeWithTheirNewBalances() {
        UUID eventId = UUID.randomUUID();

        List<Notification> messages = render(TestEvents.completedTransfer(eventId, "Tiền nhà tháng 10"));

        assertThat(messages).extracting(Notification::recipientId, Notification::template, Notification::message)
                .containsExactly(
                        tuple("alice", "BALANCE_DEBITED",
                                "PayLedger: TK ...1c00 -250,000 VND luc 15:15 03/10/2026. SD: 750,000 VND. ND: Tien nha thang 10"),
                        tuple("bob", "BALANCE_CREDITED",
                                "PayLedger: TK ...7f6a +250,000 VND luc 15:15 03/10/2026. SD: 250,000 VND. ND: Tien nha thang 10"));
        assertThat(messages).allSatisfy(m -> {
            assertThat(m.eventId()).isEqualTo(eventId);
            assertThat(m.channel()).isEqualTo(Notification.Channel.SMS);
        });
    }

    @Test
    void depositNotifiesOnlyTheCustomer() {
        List<Notification> messages = render(event(UUID.randomUUID(), "com.payledger.transfer.completed", 1,
                data("DEPOSIT", "COMPLETED", 500_000, "VND", null, account(SYSTEM_ACCOUNT, "system", "SYSTEM", -500_000L),
                        account(ALICE_ACCOUNT, "alice", "CUSTOMER", 500_000L), null)));

        assertThat(messages).singleElement().satisfies(m -> {
            assertThat(m.recipientId()).isEqualTo("alice");
            assertThat(m.message()).isEqualTo(
                    "PayLedger: TK ...1c00 +500,000 VND luc 15:15 03/10/2026. SD: 500,000 VND. ND: Nap tien");
        });
    }

    @Test
    void amountsAreShownInTheMajorUnitOfTheCurrency() {
        assertThat(BalanceChangeMessages.money(250_000, "VND")).isEqualTo("250,000 VND");
        assertThat(BalanceChangeMessages.money(250_050, "USD")).isEqualTo("2,500.50 USD");
        assertThat(BalanceChangeMessages.money(5, "EUR")).isEqualTo("0.05 EUR");
        assertThat(BalanceChangeMessages.money(-1_000_000, "VND")).isEqualTo("-1,000,000 VND");
    }

    @Test
    void textIsStrippedOfDiacriticsForGsm7() {
        assertThat(BalanceChangeMessages.withoutDiacritics("Tiền điện tháng 10, Đà Nẵng"))
                .isEqualTo("Tien dien thang 10, Da Nang");
    }

    @Test
    void rejectedTransferIsReportedToThePayerOnly() {
        List<Notification> messages = render(event(UUID.randomUUID(), "com.payledger.transfer.failed", 1,
                data("TRANSFER", "FAILED", 250_000, "VND", "Rent", account(ALICE_ACCOUNT, "alice", "CUSTOMER", null),
                        account(BOB_ACCOUNT, "bob", "CUSTOMER", null), "INSUFFICIENT_FUNDS")));

        assertThat(messages).singleElement().satisfies(m -> {
            assertThat(m.recipientId()).isEqualTo("alice");
            assertThat(m.template()).isEqualTo("PAYMENT_FAILED");
            assertThat(m.message()).isEqualTo("PayLedger: GD chuyen 250,000 VND tu TK ...1c00 luc 15:15 03/10/2026 "
                    + "KHONG THANH CONG (INSUFFICIENT_FUNDS). So du khong thay doi.");
        });
    }

    @Test
    void platformInitiatedFailuresAndNonMovingEventsNotifyNobody() {
        String failedDeposit = event(UUID.randomUUID(), "com.payledger.transfer.failed", 1,
                data("DEPOSIT", "FAILED", 500_000, "VND", null, account(SYSTEM_ACCOUNT, "system", "SYSTEM", null),
                        account(ALICE_ACCOUNT, "alice", "CUSTOMER", null), "DESTINATION_ACCOUNT_NOT_ACTIVE"));
        String created = event(UUID.randomUUID(), "com.payledger.transfer.created", 1,
                data("TRANSFER", "PENDING", 1_000, "VND", null, account(ALICE_ACCOUNT, "alice", "CUSTOMER", null),
                        account(BOB_ACCOUNT, "bob", "CUSTOMER", null), null));
        String reversed = event(UUID.randomUUID(), "com.payledger.transfer.reversed", 1,
                data("TRANSFER", "REVERSED", 1_000, "VND", null, account(ALICE_ACCOUNT, "alice", "CUSTOMER", null),
                        account(BOB_ACCOUNT, "bob", "CUSTOMER", null), null));

        assertThat(render(failedDeposit)).isEmpty();
        assertThat(render(created)).isEmpty();
        assertThat(render(reversed)).isEmpty();
    }

    private List<Notification> render(String message) {
        return BalanceChangeMessages.render(TransferEvent.parse(message, json));
    }
}
