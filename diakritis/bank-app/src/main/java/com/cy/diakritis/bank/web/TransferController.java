package com.cy.diakritis.bank.web;

import com.cy.diakritis.bank.security.CurrentUser;
import com.cy.diakritis.bank.service.BankingService;
import com.cy.diakritis.bank.web.dto.BatchRequest;
import com.cy.diakritis.bank.web.dto.DepositBreakRequest;
import com.cy.diakritis.bank.web.dto.LimitChangeRequest;
import com.cy.diakritis.bank.web.dto.PayeeRequest;
import com.cy.diakritis.bank.web.dto.TransferRequest;
import com.cy.diakritis.common.dto.Decision;
import com.cy.diakritis.common.dto.EventType;
import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.common.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer action endpoints. Each builds an {@link com.cy.diakritis.common.dto.ActionEvent} for the
 * caller's bound account from stored facts plus the request, forwards it to decision-service, and
 * returns the resulting {@link Decision}. Only CUSTOMER principals may initiate actions; the acting
 * account is always the one bound to the token (never client-supplied), preventing cross-account
 * initiation.
 */
@RestController
public class TransferController {

    private final BankingService bankingService;
    private final CurrentUser currentUser;

    public TransferController(BankingService bankingService, CurrentUser currentUser) {
        this.bankingService = bankingService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Initiate a SEPA/internal transfer",
            description = "Builds a TRANSFER ActionEvent for the caller's bound account and returns the "
                    + "decision-service verdict.")
    @PostMapping("/transfers")
    public Decision transfer(@Valid @RequestBody TransferRequest request, HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.transfer(accountId, request, EventType.TRANSFER);
    }

    @Operation(summary = "Initiate a P2P (instant) transfer",
            description = "Builds a P2P_TRANSFER ActionEvent for the caller's bound account and returns the "
                    + "decision-service verdict.")
    @PostMapping("/p2p")
    public Decision p2p(@Valid @RequestBody TransferRequest request, HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.transfer(accountId, request, EventType.P2P_TRANSFER);
    }

    @Operation(summary = "Add a beneficiary (payee)",
            description = "Registers a new payee and returns the BENEFICIARY_ADD decision.")
    @PostMapping("/payees")
    public Decision addPayee(@Valid @RequestBody PayeeRequest request, HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.addPayee(accountId, request);
    }

    @Operation(summary = "Submit a mass-payment batch",
            description = "Builds a MASS_PAYMENT ActionEvent with per-item decisions; on a business account the "
                    + "batch requires approval.")
    @PostMapping("/batches")
    public Decision batch(@Valid @RequestBody BatchRequest request, HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.massPayment(accountId, request);
    }

    @Operation(summary = "Break a term deposit",
            description = "Builds a TERM_DEPOSIT_BREAK ActionEvent for the named deposit; capped at CONFIRM with "
                    + "a purpose prompt, and frees funds onto the account posture.")
    @PostMapping("/deposits/{id}/break")
    public Decision breakDeposit(@PathVariable("id") String depositId,
                                 @Valid @RequestBody DepositBreakRequest request,
                                 HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.breakDeposit(accountId, depositId, request);
    }

    @Operation(summary = "Change a transfer limit",
            description = "Builds a LIMIT_CHANGE ActionEvent; more than doubling the current limit requires "
                    + "approval.")
    @PostMapping("/limits/change")
    public Decision changeLimit(@Valid @RequestBody LimitChangeRequest request, HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.changeLimit(accountId, request);
    }

    private String customerAccount(HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentUser.requireRole(httpRequest, Role.CUSTOMER);
        String accountId = principal.accountId();
        if (accountId == null || accountId.isBlank()) {
            throw new com.cy.diakritis.bank.security.ForbiddenException(
                    "No account is bound to this customer principal");
        }
        return accountId;
    }
}
