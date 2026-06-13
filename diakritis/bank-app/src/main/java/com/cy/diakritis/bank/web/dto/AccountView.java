package com.cy.diakritis.bank.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public view of an account returned by {@code GET /accounts/{id}}. Balances are exposed in euros
 * (cents/100); raw internal cents stay server-side.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountView(
        String accountId,
        String displayName,
        BigDecimal availableBalanceEur,
        boolean business,
        List<String> approverUserIds,
        List<TermDepositView> termDeposits,
        String source
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TermDepositView(
            String depositId,
            BigDecimal principalEur,
            java.time.Instant maturityDate,
            BigDecimal penaltyEur,
            boolean broken
    ) {
    }
}
