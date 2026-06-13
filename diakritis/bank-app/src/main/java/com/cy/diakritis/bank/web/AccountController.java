package com.cy.diakritis.bank.web;

import com.cy.diakritis.bank.security.CurrentUser;
import com.cy.diakritis.bank.security.ForbiddenException;
import com.cy.diakritis.bank.service.BankingService;
import com.cy.diakritis.bank.web.dto.AccountView;
import com.cy.diakritis.bank.web.dto.PayeeView;
import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.common.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read endpoints for account facts and saved payees. A CUSTOMER may only read the account bound to
 * their token; OPS/APPROVER may read any account.
 */
@RestController
public class AccountController {

    private final BankingService bankingService;
    private final CurrentUser currentUser;

    public AccountController(BankingService bankingService, CurrentUser currentUser) {
        this.bankingService = bankingService;
        this.currentUser = currentUser;
    }

    @GetMapping("/accounts/{id}")
    public AccountView getAccount(@PathVariable("id") String accountId, HttpServletRequest request) {
        AuthPrincipal principal = currentUser.require(request);
        assertAccountAccess(principal, accountId);
        return bankingService.getAccount(accountId);
    }

    @GetMapping("/payees")
    public List<PayeeView> getPayees(HttpServletRequest request) {
        AuthPrincipal principal = currentUser.require(request);
        String accountId = principal.accountId();
        if (accountId == null || accountId.isBlank()) {
            throw new ForbiddenException("No account is bound to this principal");
        }
        return bankingService.listPayees(accountId);
    }

    private void assertAccountAccess(AuthPrincipal principal, String accountId) {
        if (principal.role() == Role.CUSTOMER && !accountId.equals(principal.accountId())) {
            throw new ForbiddenException("Account " + accountId + " is not accessible to this customer");
        }
    }
}
