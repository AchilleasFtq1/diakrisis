package com.cy.demobank.web;

import com.cy.demobank.client.DiakrisisClientException;
import com.cy.demobank.domain.Account;
import com.cy.demobank.service.ActionResult;
import com.cy.demobank.service.BankService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * The demo-bank's server-rendered (Thymeleaf) UI. Every money action posts a form, the
 * {@link BankService} drives a live Diakrisis decision, and the verdict page renders the
 * ALLOW/CONFIRM/HOLD/BLOCK/REQUIRE_APPROVAL outcome plus the explanation. Balance changes are
 * applied only on ALLOW (or a confirmed break), exactly as the service decides.
 */
@Controller
@Validated
public class BankController {

    private final BankService bankService;

    public BankController(BankService bankService) {
        this.bankService = bankService;
    }

    /** Home: the list of demo accounts; defaults to viewing acc-A. */
    @GetMapping("/")
    public String home(Model model) {
        List<Account> accounts = bankService.accounts();
        model.addAttribute("accounts", accounts);
        return "index";
    }

    /** Account view: balance, payees, deposits, and the action forms. */
    @GetMapping("/accounts/{id}")
    public String account(@PathVariable("id") String id, Model model) {
        populateAccountModel(model, id);
        return "account";
    }

    @PostMapping("/transfer")
    public String transfer(@RequestParam("account") @NotBlank String account,
                           @RequestParam("payee") @NotBlank String payee,
                           @RequestParam("amount") @NotNull @Positive BigDecimal amount,
                           @RequestParam("rail") @NotBlank String rail,
                           Model model) {
        ActionResult result = bankService.transfer(account, payee, amount, rail);
        return renderVerdict(model, account, result);
    }

    @PostMapping("/transfer-new")
    public String transferToNew(@RequestParam("account") @NotBlank String account,
                                @RequestParam("iban") @NotBlank String iban,
                                @RequestParam(value = "resolvedName", required = false) String resolvedName,
                                @RequestParam("amount") @NotNull @Positive BigDecimal amount,
                                @RequestParam("rail") @NotBlank String rail,
                                Model model) {
        ActionResult result = bankService.transferToNew(account, iban, blankToNull(resolvedName), amount, rail);
        return renderVerdict(model, account, result);
    }

    @PostMapping("/p2p")
    public String p2p(@RequestParam("account") @NotBlank String account,
                      @RequestParam("alias") @NotBlank String alias,
                      @RequestParam("resolvedName") @NotBlank String resolvedName,
                      @RequestParam("amount") @NotNull @Positive BigDecimal amount,
                      Model model) {
        ActionResult result = bankService.p2p(account, alias, resolvedName, amount);
        return renderVerdict(model, account, result);
    }

    @PostMapping("/beneficiaries")
    public String addBeneficiary(@RequestParam("account") @NotBlank String account,
                                 @RequestParam("iban") @NotBlank String iban,
                                 @RequestParam("displayName") @NotBlank String displayName,
                                 @RequestParam(value = "resolvedName", required = false) String resolvedName,
                                 Model model) {
        ActionResult result = bankService.addBeneficiary(account, iban, displayName,
                resolvedName == null || resolvedName.isBlank() ? displayName : resolvedName);
        return renderVerdict(model, account, result);
    }

    @PostMapping("/deposits/{id}/break")
    public String breakDeposit(@PathVariable("id") String depositId,
                               @RequestParam("account") @NotBlank String account,
                               Model model) {
        ActionResult result = bankService.breakDeposit(depositId);
        return renderVerdict(model, account, result);
    }

    @PostMapping("/batches")
    public String batch(@RequestParam("account") @NotBlank String account,
                        @RequestParam("iban1") @NotBlank String iban1,
                        @RequestParam("name1") @NotBlank String name1,
                        @RequestParam("amount1") @NotNull @Positive BigDecimal amount1,
                        @RequestParam(value = "iban2", required = false) String iban2,
                        @RequestParam(value = "name2", required = false) String name2,
                        @RequestParam(value = "amount2", required = false) BigDecimal amount2,
                        @RequestParam("rail") @NotBlank String rail,
                        Model model) {
        var lines = new java.util.ArrayList<BankService.BatchLine>();
        lines.add(new BankService.BatchLine("L01", iban1, name1, amount1));
        if (iban2 != null && !iban2.isBlank() && amount2 != null) {
            lines.add(new BankService.BatchLine("L02", iban2,
                    name2 == null || name2.isBlank() ? iban2 : name2, amount2));
        }
        ActionResult result = bankService.massPayment(account, lines, rail);
        return renderVerdict(model, account, result);
    }

    // ------------------------------------------------------------------------------------------------
    // Rendering helpers + error mapping.
    // ------------------------------------------------------------------------------------------------

    private String renderVerdict(Model model, String accountId, ActionResult result) {
        model.addAttribute("result", result);
        populateAccountModel(model, accountId);
        return "verdict";
    }

    private void populateAccountModel(Model model, String accountId) {
        Account account = bankService.requireAccount(accountId);
        model.addAttribute("account", account);
        model.addAttribute("payees", bankService.payees(accountId));
        model.addAttribute("deposits", bankService.deposits(accountId));
        model.addAttribute("accounts", bankService.accounts());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** A bad request (unknown account/payee, non-positive amount) renders a friendly error page. */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public String handleBadRequest(IllegalArgumentException ex, Model model) {
        model.addAttribute("errorTitle", "Invalid request");
        model.addAttribute("errorMessage", ex.getMessage());
        model.addAttribute("accounts", bankService.accounts());
        return "error-page";
    }

    /** A Diakrisis call failure (services down, login/scoring error) renders a clear diagnostic. */
    @org.springframework.web.bind.annotation.ExceptionHandler(DiakrisisClientException.class)
    public String handleDiakrisisError(DiakrisisClientException ex, Model model) {
        model.addAttribute("errorTitle", "Diakrisis call failed");
        model.addAttribute("errorMessage", ex.getMessage());
        model.addAttribute("accounts", bankService.accounts());
        return "error-page";
    }
}
