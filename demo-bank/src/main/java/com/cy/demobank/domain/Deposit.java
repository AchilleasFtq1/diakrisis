package com.cy.demobank.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A term deposit held against a demo account. Breaking it (the kill-chain's first leg) frees the
 * principal and is scored by Diakrisis as a {@code TERM_DEPOSIT_BREAK}.
 */
public record Deposit(
        String id,
        String accountId,
        long principalCents,
        long maturityEpochMs,
        long penaltyCents,
        boolean broken
) {
    public BigDecimal principalEur() {
        return BigDecimal.valueOf(principalCents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal penaltyEur() {
        return BigDecimal.valueOf(penaltyCents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }
}
