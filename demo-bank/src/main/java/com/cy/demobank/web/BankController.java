package com.cy.demobank.web;

import com.cy.demobank.client.DiakrisisClientException;
import com.cy.demobank.domain.Account;
import com.cy.demobank.service.ActionResult;
import com.cy.demobank.service.BankService;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Meridian — the demo bank's server-rendered (Thymeleaf) online-banking UI. A customer signs in
 * (session-scoped), then navigates a real multi-page app: dashboard, account statement, a payment
 * flow, payees, a payroll file upload, and an activity feed. Every money action drives a live
 * Diakrisis decision and is recorded to the statement; balance changes apply only on an ALLOW.
 */
@Controller
@Validated
public class BankController {

    private static final String SESSION_CUSTOMER = "customer";
    private final BankService bankService;

    public BankController(BankService bankService) {
        this.bankService = bankService;
    }

    // ================================================================= auth (session)
    @GetMapping("/")
    public String landing(HttpSession session, Model model) {
        if (session.getAttribute(SESSION_CUSTOMER) != null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("accounts", bankService.accounts());
        return "login";
    }

    @PostMapping("/sign-in")
    public String signIn(@RequestParam("customer") @NotBlank String customer, HttpSession session) {
        session.setAttribute(SESSION_CUSTOMER, customer);
        return "redirect:/dashboard";
    }

    @GetMapping("/sign-out")
    public String signOut(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }

    // ================================================================= dashboard
    @GetMapping("/dashboard")
    public String dashboard(HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        shell(model, owner, "dashboard");
        model.addAttribute("accounts", bankService.accountsForOwner(owner));
        model.addAttribute("recent", bankService.recentActivity(owner, 6));
        return "dashboard";
    }

    // ================================================================= account statement
    @GetMapping("/accounts/{id}")
    public String account(@PathVariable("id") String id, HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        shell(model, owner, "accounts");
        populateAccount(model, id);
        model.addAttribute("statement", bankService.statement(id));
        return "account";
    }

    // ================================================================= pay (hub + 3 dedicated pages)
    /** Payments hub — three distinct payment journeys. */
    @GetMapping("/pay")
    public String payHub(@RequestParam(value = "account", required = false) String account,
                         HttpSession session, Model model) {
        return payPage(account, session, model, "payments");
    }

    /** Pay a saved payee (SEPA Credit Transfer). */
    @GetMapping("/pay/payee")
    public String payPayee(@RequestParam(value = "account", required = false) String account,
                           HttpSession session, Model model) {
        return payPage(account, session, model, "pay-payee");
    }

    /** Pay a new beneficiary (one-off SEPA Credit Transfer). */
    @GetMapping("/pay/new")
    public String payNew(@RequestParam(value = "account", required = false) String account,
                         HttpSession session, Model model) {
        return payPage(account, session, model, "pay-new");
    }

    /** Send to a mobile number (P2P). */
    @GetMapping("/pay/mobile")
    public String payMobile(@RequestParam(value = "account", required = false) String account,
                            HttpSession session, Model model) {
        return payPage(account, session, model, "pay-mobile");
    }

    private String payPage(String account, HttpSession session, Model model, String view) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        List<Account> accounts = bankService.accountsForOwner(owner);
        String acct = account != null ? account : (accounts.isEmpty() ? null : accounts.get(0).id());
        shell(model, owner, "pay");
        model.addAttribute("accounts", accounts);
        model.addAttribute("selectedAccount", acct);
        model.addAttribute("payees", acct == null ? List.of() : bankService.payees(acct));
        return view;
    }

    /**
     * SEPA Credit Transfer. Pay an existing payee (by {@code payee} key) or a new beneficiary by
     * {@code iban}/{@code bic}/{@code beneficiaryName}. BIC + reference are captured for the
     * payment record (a real SCT carries them); the fraud engine scores the IBAN/amount/behaviour.
     */
    @PostMapping("/pay")
    public String pay(@RequestParam("account") @NotBlank String account,
                      @RequestParam(value = "payee", required = false) String payee,
                      @RequestParam(value = "iban", required = false) String iban,
                      @RequestParam(value = "bic", required = false) String bic,
                      @RequestParam(value = "beneficiaryName", required = false) String beneficiaryName,
                      @RequestParam(value = "reference", required = false) String reference,
                      @RequestParam("amount") @NotNull @Positive BigDecimal amount,
                      @RequestParam("rail") @NotBlank String rail,
                      HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        boolean toNew = iban != null && !iban.isBlank();
        ActionResult result = toNew
                ? bankService.transferToNew(account, iban.trim(), blankToNull(beneficiaryName), amount, rail, reference)
                : bankService.transfer(account, payee, amount, rail, reference);
        String toName = toNew ? (blankToNull(beneficiaryName) == null ? iban.trim() : beneficiaryName)
                              : bankService.payees(account).stream()
                                    .filter(p -> p.cpKey().equals(payee)).map(p -> p.displayName()).findFirst().orElse(payee);
        String toIban = toNew ? iban.trim() : bankService.payees(account).stream()
                .filter(p -> p.cpKey().equals(payee)).map(p -> p.iban()).findFirst().orElse(null);
        String toBic = toNew ? blankToNull(bic) : bankService.payees(account).stream()
                .filter(p -> p.cpKey().equals(payee)).map(p -> p.bic()).findFirst().orElse(null);
        Receipt receipt = new Receipt(toName, toIban, toBic, amount, blankToNull(reference), rail);
        return verdict(model, owner, account, result, receipt);
    }

    /** A customer-facing payment receipt for the result page (no engine data). */
    public record Receipt(String to, String iban, String bic, BigDecimal amount, String reference, String rail) {
    }

    @PostMapping("/p2p")
    public String p2p(@RequestParam("account") @NotBlank String account,
                      @RequestParam("alias") @NotBlank String alias,
                      @RequestParam("resolvedName") @NotBlank String resolvedName,
                      @RequestParam("amount") @NotNull @Positive BigDecimal amount,
                      HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        ActionResult result = bankService.p2p(account, alias, resolvedName, amount);
        return verdict(model, owner, account, result,
                new Receipt(resolvedName, alias, null, amount, null, "SEPA Instant (P2P)"));
    }

    // ================================================================= lifecycle (step-up / cancel)
    /** Complete the SCA step-up on a CONFIRM: confirm the held action and execute the payment. */
    @PostMapping("/confirm-payment")
    public String confirmPayment(@RequestParam("eventId") @NotBlank String eventId,
                                 @RequestParam("account") @NotBlank String account,
                                 @RequestParam("amount") @NotNull @Positive BigDecimal amount,
                                 @RequestParam(value = "to", required = false) String to,
                                 @RequestParam(value = "iban", required = false) String iban,
                                 @RequestParam(value = "bic", required = false) String bic,
                                 @RequestParam(value = "reference", required = false) String reference,
                                 @RequestParam(value = "rail", required = false) String rail,
                                 @RequestParam(value = "code", required = false) String code,
                                 HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalArgumentException("Enter the 6-digit code we sent to your phone.");
        }
        ActionResult result = bankService.confirmPayment(account, eventId, amount);
        Receipt receipt = new Receipt(blankToNull(to), blankToNull(iban), blankToNull(bic),
                amount, blankToNull(reference), rail == null ? "SEPA" : rail);
        return verdict(model, owner, account, result, receipt);
    }

    /** Cancel a held payment, then return to the account statement (it now shows "Cancelled"). */
    @PostMapping("/cancel-payment")
    public String cancelPayment(@RequestParam("eventId") @NotBlank String eventId,
                                @RequestParam("account") @NotBlank String account,
                                HttpSession session) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        bankService.cancelPayment(account, eventId);
        return "redirect:/accounts/" + account;
    }

    // ================================================================= payees
    @GetMapping("/payees")
    public String payees(@RequestParam(value = "account", required = false) String account,
                         HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        List<Account> accounts = bankService.accountsForOwner(owner);
        String acct = account != null ? account : (accounts.isEmpty() ? null : accounts.get(0).id());
        shell(model, owner, "payees");
        model.addAttribute("accounts", accounts);
        model.addAttribute("selectedAccount", acct);
        model.addAttribute("payees", acct == null ? List.of() : bankService.payees(acct));
        return "payees";
    }

    @PostMapping("/beneficiaries")
    public String addBeneficiary(@RequestParam("account") @NotBlank String account,
                                 @RequestParam("iban") @NotBlank String iban,
                                 @RequestParam(value = "bic", required = false) String bic,
                                 @RequestParam("displayName") @NotBlank String displayName,
                                 @RequestParam(value = "resolvedName", required = false) String resolvedName,
                                 HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        ActionResult result = bankService.addBeneficiary(account, iban, blankToNull(bic), displayName,
                resolvedName == null || resolvedName.isBlank() ? displayName : resolvedName);
        return verdict(model, owner, account, result);
    }

    // ================================================================= deposits (break)
    @PostMapping("/deposits/{id}/break")
    public String breakDeposit(@PathVariable("id") String depositId,
                               @RequestParam("account") @NotBlank String account,
                               HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        ActionResult result = bankService.breakDeposit(depositId);
        return verdict(model, owner, account, result);
    }

    // ================================================================= payroll (file upload)
    @GetMapping("/payroll")
    public String payrollForm(@RequestParam(value = "account", required = false) String account,
                              HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        List<Account> accounts = bankService.accountsForOwner(owner);
        String acct = account != null ? account : (accounts.isEmpty() ? null : accounts.get(0).id());
        shell(model, owner, "payroll");
        model.addAttribute("accounts", accounts);
        model.addAttribute("selectedAccount", acct);
        return "payroll";
    }

    /** Upload a payroll CSV (one {@code iban,name,amount} per line) and run it as a mass payment. */
    @PostMapping("/payroll")
    public String payroll(@RequestParam("account") @NotBlank String account,
                          @RequestParam("rail") @NotBlank String rail,
                          @RequestParam("file") MultipartFile file,
                          HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        List<BankService.BatchLine> lines = parsePayroll(file);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("The payroll file had no valid lines (expected: iban,name,amount).");
        }
        ActionResult result = bankService.massPayment(account, lines, rail);
        return verdict(model, owner, account, result);
    }

    /** Parse a payroll CSV: {@code iban,name,amount} per line; a header row (non-numeric amount) is skipped. */
    private static List<BankService.BatchLine> parsePayroll(MultipartFile file) {
        List<BankService.BatchLine> lines = new ArrayList<>();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose a payroll file to upload.");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split(",");
                if (cols.length < 3) {
                    continue;
                }
                String iban = cols[0].trim();
                String name = cols[1].trim();
                BigDecimal amount;
                try {
                    amount = new BigDecimal(cols[2].trim());
                } catch (NumberFormatException ex) {
                    continue; // header or malformed row — skip
                }
                if (amount.signum() <= 0 || iban.isBlank()) {
                    continue;
                }
                lines.add(new BankService.BatchLine("L" + (++n), iban, name.isBlank() ? iban : name, amount));
            }
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Could not read the payroll file: " + ex.getMessage());
        }
        return lines;
    }

    // ================================================================= activity
    @GetMapping("/activity")
    public String activity(HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        shell(model, owner, "activity");
        model.addAttribute("statement", bankService.activity(owner));
        return "activity";
    }

    // ================================================================= helpers
    private static String owner(HttpSession session) {
        Object c = session.getAttribute(SESSION_CUSTOMER);
        return c == null ? null : c.toString();
    }

    /** Common chrome attributes every signed-in page needs (the sidebar). */
    private void shell(Model model, String owner, String active) {
        model.addAttribute("customer", owner);
        model.addAttribute("navActive", active);
        model.addAttribute("ownerAccounts", bankService.accountsForOwner(owner));
    }

    private String verdict(Model model, String owner, String accountId, ActionResult result) {
        return verdict(model, owner, accountId, result, null);
    }

    private String verdict(Model model, String owner, String accountId, ActionResult result, Receipt receipt) {
        shell(model, owner, "pay");
        model.addAttribute("result", result);
        model.addAttribute("receipt", receipt);
        populateAccount(model, accountId);
        return "verdict";
    }

    private void populateAccount(Model model, String accountId) {
        Account account = bankService.requireAccount(accountId);
        model.addAttribute("account", account);
        model.addAttribute("payees", bankService.payees(accountId));
        model.addAttribute("deposits", bankService.deposits(accountId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public String handleBadRequest(IllegalArgumentException ex, HttpSession session, Model model) {
        model.addAttribute("customer", owner(session));
        model.addAttribute("navActive", "");
        model.addAttribute("ownerAccounts",
                owner(session) == null ? List.of() : bankService.accountsForOwner(owner(session)));
        model.addAttribute("errorTitle", "Invalid request");
        model.addAttribute("errorMessage", ex.getMessage());
        return "error-page";
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(DiakrisisClientException.class)
    public String handleDiakrisisError(DiakrisisClientException ex, HttpSession session, Model model) {
        model.addAttribute("customer", owner(session));
        model.addAttribute("navActive", "");
        model.addAttribute("ownerAccounts",
                owner(session) == null ? List.of() : bankService.accountsForOwner(owner(session)));
        model.addAttribute("errorTitle", "Diakrisis call failed");
        model.addAttribute("errorMessage", ex.getMessage());
        return "error-page";
    }
}
