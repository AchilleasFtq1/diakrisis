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

    @PostMapping("/transfers")
    public Decision transfer(@Valid @RequestBody TransferRequest request, HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.transfer(accountId, request, EventType.TRANSFER);
    }

    @PostMapping("/p2p")
    public Decision p2p(@Valid @RequestBody TransferRequest request, HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.transfer(accountId, request, EventType.P2P_TRANSFER);
    }

    @PostMapping("/payees")
    public Decision addPayee(@Valid @RequestBody PayeeRequest request, HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.addPayee(accountId, request);
    }

    @PostMapping("/batches")
    public Decision batch(@Valid @RequestBody BatchRequest request, HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.massPayment(accountId, request);
    }

    @PostMapping("/deposits/{id}/break")
    public Decision breakDeposit(@PathVariable("id") String depositId,
                                 @Valid @RequestBody DepositBreakRequest request,
                                 HttpServletRequest httpRequest) {
        String accountId = customerAccount(httpRequest);
        return bankingService.breakDeposit(accountId, depositId, request);
    }

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
