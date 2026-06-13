package com.cy.diakritis.decision.service;

import com.cy.diakritis.common.dto.LifecycleState;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Outcome of a lifecycle transition on an action. {@code itemsExecuted} / {@code itemsHeld} are
 * populated only for batch (mass-payment) approvals; for single actions they are null and omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LifecycleResult(
        String eventId,
        LifecycleState state,
        List<String> itemsExecuted,
        List<String> itemsHeld
) {

    public static LifecycleResult of(String eventId, LifecycleState state) {
        return new LifecycleResult(eventId, state, null, null);
    }

    public static LifecycleResult batch(String eventId, LifecycleState state,
                                        List<String> itemsExecuted, List<String> itemsHeld) {
        return new LifecycleResult(eventId, state, itemsExecuted, itemsHeld);
    }
}
