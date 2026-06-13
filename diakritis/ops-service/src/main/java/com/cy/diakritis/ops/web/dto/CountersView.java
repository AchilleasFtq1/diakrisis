package com.cy.diakritis.ops.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Aggregate decision counters for the ops dashboard.
 *
 * <p>The lifecycle breakdown ({@code total} / {@code byLifecycleState}) is joined by the SDD §9.5
 * feedback-loop counters and the headline operating metrics:
 * <ul>
 *   <li>{@code confirmedSaves} — HELD actions the customer then cancelled (true catches).</li>
 *   <li>{@code falsePositives} — HELD actions released after expiry and executed unchanged.</li>
 *   <li>{@code moneySavedCents} — euro-cents of held payments the confirmed saves prevented.</li>
 *   <li>{@code exemptionRate} — fraction of decisions granted the PSD2 TRA SCA exemption.</li>
 *   <li>{@code p50LatencyMs} — median per-decision latency in milliseconds.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CountersView(
        long total,
        Map<String, Long> byLifecycleState,
        Map<String, Long> byOutcome,
        long confirmedSaves,
        long falsePositives,
        long moneySavedCents,
        double exemptionRate,
        long p50LatencyMs
) {
}
