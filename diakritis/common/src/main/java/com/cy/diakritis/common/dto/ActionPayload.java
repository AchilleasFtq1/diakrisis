package com.cy.diakritis.common.dto;

public sealed interface ActionPayload
        permits TransferPayload, MassPaymentPayload, DepositBreakPayload, BeneficiaryAddPayload, LimitChangePayload {
}
