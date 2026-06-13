package com.cy.diakritis.engine.store;

/**
 * Read-only projection of the name-indexed counterparty record that backs
 * Confirmation-of-Payee (the T4 dual key). Lets a signal compare an inbound payee name
 * against an established supplier name and detect when the IBAN behind that name has changed.
 *
 * <p>Money is integer euro-cents; timestamps are epoch-millis.
 */
public record CounterpartyByNameView(
        String normalizedName,
        String displayName,
        String establishedIban,
        String establishedCounterpartyKey,
        long payCount,
        long meanAmountCents,
        long firstSeenEpochMs,
        long lastSeenEpochMs
) {
}
