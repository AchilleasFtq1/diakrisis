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

    /** The raw engine verdict — used internally to drive the customer UI; NEVER shown to the customer. */
    public String verdict() {
        return decision == null ? null : decision.effectiveDecision();
    }

    public List<String> typologies() {
        return decision == null ? List.of() : decision.typologies();
    }

    // ---------------------------------------------------------------------------------------------
    // Customer-facing view-model. The bank shows ordinary banking language — never the engine
    // verdict, score, friction code, typologies, reason code or latency.
    // ---------------------------------------------------------------------------------------------

    /** Customer status word for the result + statement (Sent / Pending / Paused / Declined / …). */
    public String statusLabel() {
        String v = verdict();
        if (v == null) {
            return applied ? "Sent" : "Pending";
        }
        return switch (v) {
            case "ALLOW" -> "Sent";
            case "CONFIRM" -> "Pending";
            case "HOLD" -> "Paused";
            case "REQUIRE_APPROVAL" -> "Awaiting approval";
            case "BLOCK" -> "Declined";
            default -> applied ? "Sent" : "Pending";
        };
    }

    /** CSS modifier for the status (sent / pending / paused / approval / declined). */
    public String statusClass() {
        return switch (statusLabel()) {
            case "Sent" -> "sent";
            case "Paused" -> "paused";
            case "Awaiting approval" -> "approval";
            case "Declined" -> "declined";
            default -> "pending";
        };
    }

    /** The result-page headline in customer language. */
    public String headline() {
        return switch (statusLabel()) {
            case "Sent" -> "Payment sent";
            case "Paused" -> "We've paused this payment to protect you";
            case "Awaiting approval" -> "This payment needs a second approval";
            case "Declined" -> "We couldn't make this payment";
            default -> "Confirm it's really you";
        };
    }

    /** The result-page body in customer language. */
    public String body() {
        return switch (statusLabel()) {
            case "Sent" -> "Your payment has been sent. Keep the reference for your records.";
            case "Paused" -> "We've held this payment while you check it's genuine. It has not been sent.";
            case "Awaiting approval" -> "We'll call you to confirm this payment is genuine before it's sent.";
            case "Declined" -> "We weren't able to process this payment. Please contact us if you think this is wrong.";
            default -> "For your security, please verify it's you before we send this payment.";
        };
    }

    public boolean showSca() { return "CONFIRM".equals(verdict()); }
    public boolean showCancel() { return "HOLD".equals(verdict()); }
    public boolean showApproval() { return "REQUIRE_APPROVAL".equals(verdict()); }
    public boolean isSent() { return "Sent".equals(statusLabel()); }
    public boolean isPaused() { return "Paused".equals(statusLabel()); }

    /**
     * The customer-facing warning that NAMES the scam pattern (the product's whole point) on a paused
     * payment. This is plain-English prose written for the victim — not an engine code.
     */
    public String scamWarning() {
        return decision == null ? null : decision.customerExplanation();
    }

    /** A short customer-facing payment reference for the receipt (derived from the event id). */
    public String confirmationId() {
        if (decision == null || decision.eventId() == null) {
            return null;
        }
        String id = decision.eventId();
        String tail = id.length() <= 8 ? id : id.substring(id.length() - 8);
        return "MRD-" + tail.toUpperCase();
    }
}
