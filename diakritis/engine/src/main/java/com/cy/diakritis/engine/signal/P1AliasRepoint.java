package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.Addressing;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.engine.band.Weights;

import java.util.Optional;

/**
 * P1 — alias re-point: an addressing alias (a phone number / e-mail used as a P2P payee) now resolves
 * to a DIFFERENT underlying account than it resolved to in its own history. That is the SIM-swap /
 * alias-hijack tell — the victim still "pays Mary's number", but the number was ported to the
 * attacker's account. Carries the engine's heaviest single weight ({@link Weights#P1}) because the
 * identity the customer trusts has silently changed underneath them.
 *
 * <p>Fires (1.0) only for alias-addressed counterparties (MSISDN / EMAIL) that HAVE a prior
 * resolution on record AND whose resolved account ref now differs from that history. A first-ever
 * resolution (no history) or an unchanged resolution scores 0.
 */
public final class P1AliasRepoint implements Signal {

    @Override
    public String id() {
        return "P1";
    }

    @Override
    public double weight() {
        return Weights.P1;
    }

    @Override
    public double value(SignalContext ctx) {
        Counterparty cp = ctx.counterparty();
        if (cp == null || !isAlias(cp.addressing())) {
            return 0.0;
        }
        String alias = cp.value();
        if (alias == null || alias.isBlank()) {
            return 0.0;
        }
        String currentResolved = cp.resolvedAccountRef();
        if (currentResolved == null || currentResolved.isBlank()) {
            // Alias did not resolve to an account this time → nothing to compare against.
            return 0.0;
        }
        Optional<String> priorResolved = ctx.obs().lastResolvedAccountRefForAlias(ctx.accountId(), alias);
        if (priorResolved.isEmpty()) {
            // First time we see this alias resolve → no re-point, just a new payee (B1's job).
            return 0.0;
        }
        return priorResolved.get().equals(currentResolved) ? 0.0 : 1.0;
    }

    private static boolean isAlias(Addressing addressing) {
        return addressing == Addressing.MSISDN || addressing == Addressing.EMAIL;
    }
}
