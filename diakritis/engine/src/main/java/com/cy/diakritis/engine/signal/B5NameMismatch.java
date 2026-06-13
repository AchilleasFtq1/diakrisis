package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.engine.band.Weights;
import com.cy.diakritis.engine.store.CounterpartyByNameView;

import java.util.Optional;

/**
 * B5 — Confirmation-of-Payee name / IBAN mismatch (the invoice-redirection tell). Fires (1.0) when
 * the inbound payee presents a resolved name that matches an established supplier name on this
 * account, but the IBAN (counterparty key) behind that name has changed. That is the classic
 * "same supplier, new bank details" fraud pattern.
 */
public final class B5NameMismatch implements Signal {

    @Override
    public String id() {
        return "B5";
    }

    @Override
    public double weight() {
        return Weights.B5;
    }

    @Override
    public double value(SignalContext ctx) {
        Counterparty cp = counterpartyOf(ctx);
        if (cp == null) {
            return 0.0;
        }
        String resolvedName = cp.resolvedName();
        if (resolvedName == null || resolvedName.isBlank()) {
            return 0.0;
        }

        String normalized = Identity.normalizeName(resolvedName);
        Optional<CounterpartyByNameView> established = ctx.store().byName(ctx.accountId(), normalized);
        if (established.isEmpty()) {
            // Name unknown to the account → no CoP-expected name to disagree with.
            return 0.0;
        }

        CounterpartyByNameView view = established.get();
        String establishedKey = view.establishedCounterpartyKey();
        String establishedIban = view.establishedIban();

        // Mismatch if the established identity (key or IBAN) differs from what is being paid now.
        boolean keyDiffers = establishedKey != null && !establishedKey.equals(ctx.cpKey());
        boolean ibanDiffers = establishedIban != null
                && cp.resolvedAccountRef() != null
                && !establishedIban.equals(cp.resolvedAccountRef());

        return (keyDiffers || ibanDiffers) ? 1.0 : 0.0;
    }

    private static Counterparty counterpartyOf(SignalContext ctx) {
        return switch (ctx.event().payload()) {
            case com.cy.diakritis.common.dto.TransferPayload t -> t.counterparty();
            case com.cy.diakritis.common.dto.BeneficiaryAddPayload b -> b.counterparty();
            default -> null;
        };
    }
}
