package com.cy.diakritis.bank.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Request to break a term deposit. The deposit id comes from the path; principal/maturity/penalty
 * are looked up from the stored {@link com.cy.diakritis.common.persistence.AccountItem}.
 */
public record DepositBreakRequest(
        @NotNull @Valid SessionRequest session
) {
}
