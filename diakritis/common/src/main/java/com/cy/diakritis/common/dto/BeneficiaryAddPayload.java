package com.cy.diakritis.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BeneficiaryAddPayload(
        @NotNull @Valid Counterparty counterparty
) implements ActionPayload {
}
