package com.cy.demobank.service;

import com.cy.demobank.client.dto.DecisionResponse;

import java.util.List;

/**
 * The outcome of an attempted money action: the live Diakrisis verdict plus whether demo-bank
 * actually applied the balance change. {@code applied} is true only when the verdict permitted
 * execution (an ALLOW); every other verdict (CONFIRM/HOLD/BLOCK/REQUIRE_APPROVAL) renders the
 * explanation without mutating the account.
 *
 * @param decision  the verdict + explanation returned by the decision-service.
 * @param applied   whether the balance change was executed.
 * @param message   a human-readable summary of what demo-bank did (executed / blocked / etc.).
 * @param eventType the action type that was scored (TRANSFER, TERM_DEPOSIT_BREAK, …).
 */
public record ActionResult(
        DecisionResponse decision,
        boolean applied,
        String message,
        String eventType
) {

    public String verdict() {
        return decision == null ? null : decision.effectiveDecision();
    }

    public List<String> typologies() {
        return decision == null ? List.of() : decision.typologies();
    }
}
