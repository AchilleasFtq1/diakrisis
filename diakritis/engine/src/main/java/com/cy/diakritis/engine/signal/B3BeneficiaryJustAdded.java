package com.cy.diakritis.engine.signal;

import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.engine.band.Weights;

import java.time.Instant;

/**
 * B3 — the payee was created within the current session, moments before being paid. Coached-scam
 * victims are walked through "add this new payee, now send" in one sitting; that just-in-time
 * creation is a strong tell. Fires (1.0) when either the counterparty's
 * {@code beneficiaryCreatedAt} or a recorded in-session beneficiary-add falls within the recent
 * window before {@code now}.
 */
public final class B3BeneficiaryJustAdded implements Signal {

    /** A payee added this recently before paying it counts as "just added". */
    private static final long RECENT_WINDOW_MS = 15L * 60L * 1000L;

    @Override
    public String id() {
        return "B3";
    }

    @Override
    public double weight() {
        return Weights.B3;
    }

    @Override
    public double value(SignalContext ctx) {
        Instant now = ctx.now();
        long nowMs = now.toEpochMilli();

        Counterparty cp = counterpartyOf(ctx);
        if (cp != null && cp.beneficiaryCreatedAt() != null) {
            long createdMs = cp.beneficiaryCreatedAt().toEpochMilli();
            long age = nowMs - createdMs;
            if (age >= 0 && age <= RECENT_WINDOW_MS) {
                return 1.0;
            }
        }

        String sessionId = ctx.event().context() == null ? null : ctx.event().context().sessionId();
        if (ctx.runtime().beneficiaryAddedInSession(sessionId, nowMs, RECENT_WINDOW_MS)) {
            return 1.0;
        }
        return 0.0;
    }

    private static Counterparty counterpartyOf(SignalContext ctx) {
        return switch (ctx.event().payload()) {
            case com.cy.diakritis.common.dto.TransferPayload t -> t.counterparty();
            case com.cy.diakritis.common.dto.BeneficiaryAddPayload b -> b.counterparty();
            default -> null;
        };
    }
}
