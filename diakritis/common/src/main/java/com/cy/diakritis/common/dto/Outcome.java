package com.cy.diakritis.common.dto;

/**
 * The labelled result of a decision's lifecycle, the training signal in the SDD §9.5
 * {@code decisions → outcomes → calibration} loop. Each value is the realised judgement of an
 * earlier verdict, recorded when the action's lifecycle reaches a terminal state.
 *
 * <ul>
 *   <li>{@link #CONFIRMED_SAVE} — a HELD action the customer then cancelled: the hold was a true
 *       catch (the cooling-off interruption prevented a payment the customer agreed was wrong).</li>
 *   <li>{@link #FALSE_POSITIVE} — a HELD action released after its hold expired and executed
 *       unchanged: the hold interrupted a legitimate payment (a friction cost, no fraud caught).</li>
 *   <li>{@link #APPROVED} — a four-eyes REQUIRE_APPROVAL action the designated approver signed off:
 *       the approver judged it legitimate.</li>
 *   <li>{@link #REJECTED} — a four-eyes REQUIRE_APPROVAL action the approver declined: the approver
 *       judged it fraudulent / unauthorised.</li>
 * </ul>
 */
public enum Outcome {
    CONFIRMED_SAVE,
    FALSE_POSITIVE,
    APPROVED,
    REJECTED
}
