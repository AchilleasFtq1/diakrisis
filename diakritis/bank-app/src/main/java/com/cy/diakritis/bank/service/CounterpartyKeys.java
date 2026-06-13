package com.cy.diakritis.bank.service;

import com.cy.diakritis.common.dto.Addressing;
import com.cy.diakritis.common.dto.Counterparty;

/**
 * Derives the engine counterparty key, matching the engine's identity rule exactly:
 * {@code resolvedAccountRef} when present, else {@code addressing + "|" + value}. Used to align the
 * payee storage sort key with how the engine looks up counterparty baselines.
 */
public final class CounterpartyKeys {

    private CounterpartyKeys() {
    }

    public static String of(Addressing addressing, String value, String resolvedAccountRef) {
        if (resolvedAccountRef != null && !resolvedAccountRef.isBlank()) {
            return resolvedAccountRef;
        }
        return addressing + "|" + value;
    }

    public static String of(Counterparty counterparty) {
        return of(counterparty.addressing(), counterparty.value(), counterparty.resolvedAccountRef());
    }
}
