package com.payledger.notification;

import com.payledger.notification.TransferEvent.Account;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Turns transfer events into the balance-change SMS ("biến động số dư") that Vietnamese banks send after every
 * movement, e.g.
 *
 * <pre>PayLedger: TK ...8b10 -250,000 VND luc 15:15 03/10/2026. SD: 750,000 VND. ND: Tien nha thang 10</pre>
 *
 * <ul>
 *   <li>Every customer whose balance changed gets one message: the payer a debit, the payee a credit. SYSTEM
 *       accounts are the platform's own and get none.</li>
 *   <li>A rejected customer transfer is reported to the payer. Nothing is sent for {@code created} or
 *       {@code reversed}: no balance changed (the reversal's own {@code completed} event reports the refund).</li>
 *   <li>The text has no diacritics, like real SMS banking: a message with Vietnamese diacritics must be sent as
 *       Unicode (70 characters per SMS segment) instead of GSM-7 (160), which more than doubles the cost.</li>
 *   <li>Times are shown in Vietnam time and account ids are masked to their last 4 characters.</li>
 * </ul>
 */
final class BalanceChangeMessages {

    static final String BALANCE_DEBITED = "BALANCE_DEBITED";
    static final String BALANCE_CREDITED = "BALANCE_CREDITED";
    static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private BalanceChangeMessages() {
    }

    static List<Notification> render(TransferEvent event) {
        return switch (event.type()) {
            case TransferEvent.COMPLETED -> balanceChanges(event);
            case TransferEvent.FAILED -> failedPayment(event);
            default -> List.of();
        };
    }

    private static List<Notification> balanceChanges(TransferEvent event) {
        TransferEvent.Data t = event.data();
        List<Notification> messages = new ArrayList<>(2);
        if (changed(t.sourceAccount())) {
            messages.add(Notification.sms(event.id(), t.sourceAccount().ownerId(), BALANCE_DEBITED,
                    balanceChange(event, t.sourceAccount(), "-")));
        }
        if (changed(t.destinationAccount())) {
            messages.add(Notification.sms(event.id(), t.destinationAccount().ownerId(), BALANCE_CREDITED,
                    balanceChange(event, t.destinationAccount(), "+")));
        }
        return messages;
    }

    private static List<Notification> failedPayment(TransferEvent event) {
        TransferEvent.Data t = event.data();
        // Deposits and reversals are started by the platform, not the customer: their failures go to operations.
        if (!"TRANSFER".equals(t.type()) || t.sourceAccount() == null || !t.sourceAccount().isCustomer()) {
            return List.of();
        }
        String message = "PayLedger: GD chuyen %s tu TK %s luc %s KHONG THANH CONG (%s). So du khong thay doi."
                .formatted(money(t.amount(), t.currency()), mask(t.sourceAccount()), localTime(event), t.failureCode());
        return List.of(Notification.sms(event.id(), t.sourceAccount().ownerId(), PAYMENT_FAILED, message));
    }

    private static String balanceChange(TransferEvent event, Account account, String sign) {
        TransferEvent.Data t = event.data();
        return "PayLedger: TK %s %s%s luc %s. SD: %s. ND: %s".formatted(mask(account), sign,
                money(t.amount(), t.currency()), localTime(event), money(account.balanceAfter(), t.currency()),
                remittanceInfo(t));
    }

    private static boolean changed(Account account) {
        return account != null && account.isCustomer() && account.balanceAfter() != null;
    }

    private static String remittanceInfo(TransferEvent.Data t) {
        if (t.description() != null && !t.description().isBlank()) {
            return withoutDiacritics(t.description().strip());
        }
        return switch (t.type()) {
            case "DEPOSIT" -> "Nap tien";
            case "REVERSAL" -> "Hoan tien GD " + shortId(t.reversalOf() == null ? null : t.reversalOf().toString());
            default -> "Chuyen tien";
        };
    }

    /** Amount in the currency's major unit: VND has no minor unit (250,000 VND), USD has cents (2,500.00 USD). */
    static String money(long minorUnits, String currency) {
        int digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        DecimalFormat format = new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits),
                DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(BigDecimal.valueOf(minorUnits, digits)) + " " + currency;
    }

    static String withoutDiacritics(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        // "đ" is a letter of its own, not "d" plus a combining mark, so NFD leaves it alone.
        return decomposed.replace('đ', 'd').replace('Đ', 'D');
    }

    private static String mask(Account account) {
        return "..." + shortId(account.accountId().toString());
    }

    private static String shortId(String id) {
        return id == null ? "" : id.substring(Math.max(0, id.length() - 4));
    }

    private static String localTime(TransferEvent event) {
        return TIME.format(event.time().atZone(VIETNAM));
    }
}
