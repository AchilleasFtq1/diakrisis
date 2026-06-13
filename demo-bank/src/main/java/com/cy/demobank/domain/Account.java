package com.cy.demobank.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A demo bank account held in SQLite. Balances are stored as integer cents to avoid float drift;
 * {@link #availableBalanceEur()} converts to euros for the wire / display.
 */
public record Account(
        String id,
        String displayName,
        long availableBalanceCents,
        String ownerUser,
        boolean business,
        boolean vulnerable
) {
    public BigDecimal availableBalanceEur() {
        return BigDecimal.valueOf(availableBalanceCents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }
}
