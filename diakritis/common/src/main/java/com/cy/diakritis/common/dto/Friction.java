package com.cy.diakritis.common.dto;

/**
 * The friction the bank must apply for a verdict — the "what to do", proportional to risk. Each rung
 * is calibrated to defeat a different attacker:
 *
 * <ul>
 *   <li>{@link #NONE} — ALLOW: execute silently (SCA-exempt where PSD2 RTS Art.18 TRA permits).</li>
 *   <li>{@link #SCA_STEP_UP} — CONFIRM: re-authenticate with a second factor (a step-up that defeats a
 *       hijacked session / account takeover, where the criminal holds the session but not the factor)
 *       plus a one-tap purpose prompt. Useless against an APP scam — the genuine victim passes SCA —
 *       which is precisely why the heavier rung exists.</li>
 *   <li>{@link #SECOND_APPROVAL} — REQUIRE_APPROVAL: a second authorised person (business four-eyes) or
 *       the bank's call-center/admin verifies out-of-band (e.g. phones the customer) before it executes;
 *       the initiator can never approve their own.</li>
 *   <li>{@link #FREEZE_AND_WARN} — HOLD: freeze the payment (it never auto-sends), name the scam pattern
 *       to the customer, and offer a one-tap cancel. This is the APP-scam defence: it does not ask
 *       "are you you?" (the victim is) — it breaks the manipulation and won't move the money.</li>
 *   <li>{@link #STOP_AND_REVIEW} — BLOCK: stop the payment and route it to manual fraud review.</li>
 * </ul>
 */
public enum Friction {
    NONE,
    SCA_STEP_UP,
    SECOND_APPROVAL,
    FREEZE_AND_WARN,
    STOP_AND_REVIEW
}
