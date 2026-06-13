package com.cy.diakritis.bank.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conversions between stored integer euro-cents and the euro {@link BigDecimal} amounts used in
 * DTOs. Centralized so rounding policy (half-up to 2 dp on the way in, exact on the way out) is
 * applied consistently.
 */
public final class MoneyConversions {

    private static final BigDecimal CENTS_PER_EURO = BigDecimal.valueOf(100);

    private MoneyConversions() {
    }

    public static long eurToCents(BigDecimal eur) {
        if (eur == null) {
            throw new IllegalArgumentException("eur amount must not be null");
        }
        return eur.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static BigDecimal centsToEur(long cents) {
        return BigDecimal.valueOf(cents).divide(CENTS_PER_EURO);
    }
}
