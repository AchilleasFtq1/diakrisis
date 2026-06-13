package com.cy.demobank.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * A recorded money action and the live Diakrisis verdict it produced — one row of the bank's
 * statement / activity feed. Persisted by {@code TransactionRepository} after every action so the
 * UI can render real history rather than a single scenario result.
 */
public record Txn(
        String id,
        String accountId,
        String ownerUser,
        String kind,
        String counterpartyName,
        String counterpartyRef,
        String reference,
        long amountCents,
        String rail,
        String verdict,
        String friction,
        String reasonCode,
        String scamPattern,
        boolean applied,
        long createdEpochMs
) {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    public BigDecimal amountEur() {
        return BigDecimal.valueOf(amountCents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    /** Human timestamp for the statement rows. */
    public String when() {
        return STAMP.format(Instant.ofEpochMilli(createdEpochMs));
    }

    /** True when the money actually left the account (an ALLOW that executed). */
    public boolean outgoing() {
        return applied;
    }

    /**
     * Customer-facing payment status — the engine verdict is NEVER shown to the customer; it is
     * mapped to ordinary bank language (the kind judges see in their own banking app).
     */
    public String status() {
        if (verdict == null) {
            return applied ? "Sent" : "Not sent";
        }
        return switch (verdict) {
            case "ALLOW" -> "Sent";
            case "CONFIRM" -> applied ? "Sent" : "Pending";
            case "HOLD" -> "Paused";
            case "REQUIRE_APPROVAL" -> "Awaiting approval";
            case "BLOCK" -> "Declined";
            default -> applied ? "Sent" : "Pending";
        };
    }

    /** CSS modifier for the status pill (sent / pending / paused / declined / approval). */
    public String statusClass() {
        return switch (status()) {
            case "Sent" -> "sent";
            case "Pending" -> "pending";
            case "Paused" -> "paused";
            case "Awaiting approval" -> "approval";
            case "Declined" -> "declined";
            default -> "pending";
        };
    }

    /** A friendly type label for the statement (no engine wording). */
    public String typeLabel() {
        return switch (kind) {
            case "TRANSFER" -> "Transfer";
            case "P2P" -> "Mobile payment";
            case "PAYEE_ADD" -> "New payee";
            case "DEPOSIT_BREAK" -> "Deposit break";
            case "PAYROLL" -> "Payroll";
            default -> kind;
        };
    }
}
