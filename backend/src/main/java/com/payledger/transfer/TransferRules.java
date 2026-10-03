package com.payledger.transfer;

import com.payledger.account.Account;
import com.payledger.account.AccountStatus;
import com.payledger.account.AccountType;

import java.util.List;
import java.util.Optional;

/**
 * Business rules evaluated after both accounts are locked, so balances and statuses cannot change between
 * the check and the posting. A violation is not an exception: the transfer is recorded as FAILED with the
 * returned code, which gives operations and fraud monitoring a trail of rejected attempts.
 *
 * <p>Checks run in the order a bank would report them: account eligibility, status, currency, funds.
 */
final class TransferRules {

    static final String ACCOUNT_TYPE_NOT_ALLOWED = "ACCOUNT_TYPE_NOT_ALLOWED";
    static final String SOURCE_ACCOUNT_NOT_ACTIVE = "SOURCE_ACCOUNT_NOT_ACTIVE";
    static final String DESTINATION_ACCOUNT_NOT_ACTIVE = "DESTINATION_ACCOUNT_NOT_ACTIVE";
    static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    /** Every code a rejection can carry. */
    static final List<String> FAILURE_CODES = List.of(ACCOUNT_TYPE_NOT_ALLOWED, SOURCE_ACCOUNT_NOT_ACTIVE,
            DESTINATION_ACCOUNT_NOT_ACTIVE, CURRENCY_MISMATCH, INSUFFICIENT_FUNDS);

    record Rejection(String code, String reason) {
    }

    private TransferRules() {
    }

    static Optional<Rejection> check(Transfer transfer, Account source, Account destination) {
        return checkTypes(transfer, source, destination)
                .or(() -> checkStatus(transfer, source, SOURCE_ACCOUNT_NOT_ACTIVE))
                .or(() -> checkStatus(transfer, destination, DESTINATION_ACCOUNT_NOT_ACTIVE))
                .or(() -> checkCurrency(transfer, source))
                .or(() -> checkCurrency(transfer, destination))
                .or(() -> checkFunds(transfer, source));
    }

    // SYSTEM accounts may go negative, so letting a customer move money out of one would mint money.
    private static Optional<Rejection> checkTypes(Transfer transfer, Account source, Account destination) {
        boolean allowed = switch (transfer.getType()) {
            case TRANSFER -> source.getType() == AccountType.CUSTOMER && destination.getType() == AccountType.CUSTOMER;
            case DEPOSIT -> source.getType() == AccountType.SYSTEM && destination.getType() == AccountType.CUSTOMER;
            // Mirrors a movement that already passed these checks.
            case REVERSAL -> true;
        };
        return allowed ? Optional.empty() : Optional.of(new Rejection(ACCOUNT_TYPE_NOT_ALLOWED,
                transfer.getType() + " is not allowed between a " + source.getType()
                        + " and a " + destination.getType() + " account"));
    }

    /**
     * Customer-initiated movements need ACTIVE accounts. A reversal is an operator action, typically to
     * claw back funds from an account frozen for fraud, so it only refuses CLOSED accounts.
     */
    private static Optional<Rejection> checkStatus(Transfer transfer, Account account, String code) {
        boolean allowed = transfer.getType() == TransferType.REVERSAL
                ? account.getStatus() != AccountStatus.CLOSED
                : account.getStatus() == AccountStatus.ACTIVE;
        return allowed ? Optional.empty()
                : Optional.of(new Rejection(code, "Account " + account.getId() + " is " + account.getStatus()));
    }

    private static Optional<Rejection> checkCurrency(Transfer transfer, Account account) {
        return account.getCurrency().equals(transfer.getCurrency()) ? Optional.empty()
                : Optional.of(new Rejection(CURRENCY_MISMATCH, "Account " + account.getId() + " holds "
                + account.getCurrency() + " but the transfer is in " + transfer.getCurrency()));
    }

    private static Optional<Rejection> checkFunds(Transfer transfer, Account source) {
        return source.canDebit(transfer.getAmount()) ? Optional.empty()
                : Optional.of(new Rejection(INSUFFICIENT_FUNDS, "Account " + source.getId()
                + " has insufficient funds for " + transfer.getAmount() + " " + transfer.getCurrency()));
    }
}
