package com.cy.diakritis.bank.web.dto;

import com.cy.diakritis.common.dto.Addressing;
import com.cy.diakritis.common.dto.Rail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * A customer-initiated transfer (also reused for P2P). The counterparty is identified by
 * addressing+value; if it matches a saved payee the bank enriches resolved name/IBAN and the
 * beneficiary-created timestamp from stored facts.
 */
public record TransferRequest(
        @NotNull Addressing addressing,
        @NotBlank String value,
        String resolvedAccountRef,
        @NotNull @Positive BigDecimal amountEur,
        @NotNull Rail rail,
        @NotNull @Valid SessionRequest session
) {
}
