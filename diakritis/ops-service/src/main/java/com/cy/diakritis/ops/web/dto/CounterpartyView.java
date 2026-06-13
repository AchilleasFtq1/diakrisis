package com.cy.diakritis.ops.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A flagged beneficiary/counterparty in the ops mule view. The flag fields come from
 * {@code CounterpartyReputation} (the engine's running reputation); name/iban/fan-in are joined from
 * the per-account {@code CounterpartyBaseline} rows, so {@code fanInAccounts} reveals how many distinct
 * accounts have paid this counterparty — the fan-in tell behind {@code mule_fan_out}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CounterpartyView(
        String counterpartyKey,
        String name,
        String iban,
        String worstOutcome,
        long flagCount,
        Instant lastFlaggedAt,
        int fanInAccounts,
        long payCount,
        BigDecimal meanAmountEur
) {
}
