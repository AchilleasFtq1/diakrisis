package com.cy.demobank.web;

import com.cy.demobank.client.DiakrisisClientException;
import com.cy.demobank.domain.Account;
import com.cy.demobank.domain.Payee;
import com.cy.demobank.service.AccessDeniedException;
import com.cy.demobank.service.ActionResult;
import com.cy.demobank.service.BankService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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
        // The demo authenticates by profile selection (no credential store). Even so, do not trust an
        // arbitrary owner string from the form as the session identity: only an actual account owner
        // may be established as the signed-in customer, so a forged "customer" value cannot impersonate
        // a non-existent or privileged identity. Per-request ownership is still enforced downstream.
        if (!bankService.isKnownOwner(customer)) {
            throw new IllegalArgumentException("Unknown customer profile.");
        }
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
        // Authorize: the signed-in customer must own this account before any of its data is read.
        bankService.requireOwnedAccount(id, owner);
        shell(model, owner, "accounts");
        populateAccount(model, id);
        model.addAttribute("statement", bankService.statement(id, owner));
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
        if (acct != null) {
            // A request-supplied account must belong to the signed-in customer before its payees load.
            bankService.requireOwnedAccount(acct, owner);
        }
        shell(model, owner, "pay");
        model.addAttribute("accounts", accounts);
        model.addAttribute("selectedAccount", acct);
        model.addAttribute("payees", acct == null ? List.of() : bankService.payeesForOwner(acct, owner));
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
                      @RequestParam("amount") @NotNull @Positive @Digits(integer = 19, fraction = 2) BigDecimal amount,
                      @RequestParam("rail") @NotBlank String rail,
                      HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        // Authorize the source account up front; the service re-checks ownership as defense in depth.
        bankService.requireOwnedAccount(account, owner);
        boolean toNew = iban != null && !iban.isBlank();
        ActionResult result = toNew
                ? bankService.transferToNew(owner, account, iban.trim(), blankToNull(beneficiaryName), amount, rail, reference)
                : bankService.transfer(owner, account, payee, amount, rail, reference);
        List<Payee> ownedPayees = bankService.payeesForOwner(account, owner);
        String toName = toNew ? (blankToNull(beneficiaryName) == null ? iban.trim() : beneficiaryName)
                              : ownedPayees.stream()
                                    .filter(p -> p.cpKey().equals(payee)).map(p -> p.displayName()).findFirst().orElse(payee);
        String toIban = toNew ? iban.trim() : ownedPayees.stream()
                .filter(p -> p.cpKey().equals(payee)).map(p -> p.iban()).findFirst().orElse(null);
        String toBic = toNew ? blankToNull(bic) : ownedPayees.stream()
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
                      @RequestParam("amount") @NotNull @Positive @Digits(integer = 19, fraction = 2) BigDecimal amount,
                      HttpSession session, Model model) {
        String owner = owner(session);
        if (owner == null) {
            return "redirect:/";
        }
        bankService.requireOwnedAccount(account, owner);
        ActionResult result = bankService.p2p(owner, account, alias, resolvedName, amount);
        return verdict(model, owner, account, result,
                new Receipt(resolvedName, alias, null, amount, null, "SEPA Instant (P2P)"));
    }

    // ================================================================= lifecycle (step-up / cancel)
    /** Complete the SCA step-up on a CONFIRM: confirm the held action and execute the payment. */
    @PostMapping("/confirm-payment")
    public String confirmPayment(@RequestParam("eventId") @NotBlank String eventId,
                                 @RequestParam("account") @NotBlank String account,
                                 // A step-up may complete a non-payment action (e.g. a term-deposit break) that
                                 // carries no transfer amount, so the form posts 0/absent. The service treats the
                                 // posted amount as a non-authoritative cross-check against the stored, originally
                                 // scored amount — so accept absent/zero here and let the service be the guard.
                                 @RequestParam(value = "amount", required = false) @PositiveOrZero @Digits(integer = 19, fraction = 2) BigDecimal amount,
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
        // The service authorizes the account + pending event against this owner and debits the stored
        // (originally scored) amount; the posted amount is only cross-checked, never trusted as truth.
        ActionResult result = bankService.confirmPayment(owner, account, eventId, amount);
        // A confirmed term-deposit break is not an outbound payment, so it has no payee/amount receipt —
        // render the confirmation without a (misleading, zero-amount) payment-details panel.
        Receipt receipt = "TERM_DEPOSIT_BREAK".equals(result.eventType())
                ? null
                : new Receipt(blankToNull(to), blankToNull(iban), blankToNull(bic),
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
        bankService.cancelPayment(owner, account, eventId);
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
        if (acct != null) {
            bankService.requireOwnedAccount(acct, owner);
        }
        shell(model, owner, "payees");
        model.addAttribute("accounts", accounts);
        model.addAttribute("selectedAccount", acct);
        model.addAttribute("payees", acct == null ? List.of() : bankService.payeesForOwner(acct, owner));
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
        bankService.requireOwnedAccount(account, owner);
        ActionResult result = bankService.addBeneficiary(owner, account, iban, blankToNull(bic), displayName,
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
        // Authorize the account the verdict page will render, and break the deposit only if the signed-in
        // customer owns the deposit's account (verified inside the service against the deposit row).
        bankService.requireOwnedAccount(account, owner);
        ActionResult result = bankService.breakDeposit(owner, depositId);
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
        if (acct != null) {
            bankService.requireOwnedAccount(acct, owner);
        }
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
        bankService.requireOwnedAccount(account, owner);
        List<BankService.BatchLine> lines = parsePayroll(file);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("The payroll file had no valid lines (expected: iban,name,amount).");
        }
        ActionResult result = bankService.massPayment(owner, account, lines, rail);
        return verdict(model, owner, account, result);
    }

    /**
     * Parse a payroll CSV of {@code iban,name,amount} per line. Uses an RFC-4180-aware field splitter so
     * a value containing a comma (e.g. a quoted name {@code "Acme, Inc"} or a thousands-separated amount
     * {@code "1,000.00"}) does not shift the columns and corrupt the amount. The first line is treated as
     * a header only when its amount field is non-numeric; otherwise it is a data row.
     *
     * <p>Malformed rows are NOT silently dropped — every bad line (wrong field count, unparseable or
     * non-positive amount, sub-cent precision, blank IBAN) is collected with its line number and reason,
     * and the whole upload is rejected so a partial payroll can never run as if it were complete.
     */
    private static List<BankService.BatchLine> parsePayroll(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose a payroll file to upload.");
        }
        List<BankService.BatchLine> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            int dataRows = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                List<String> cols = parseCsvLine(line);
                if (cols.size() != 3) {
                    errors.add("line " + lineNo + ": expected 3 columns (iban,name,amount) but found " + cols.size());
                    continue;
                }
                String iban = cols.get(0).trim();
                String name = cols.get(1).trim();
                String rawAmount = cols.get(2).trim();
                BigDecimal amount;
                try {
                    amount = new BigDecimal(rawAmount);
                } catch (NumberFormatException ex) {
                    // The very first non-blank line with a non-numeric amount is the header row — skip it
                    // silently; any later non-numeric amount is a genuine error.
                    if (lineNo == 1) {
                        continue;
                    }
                    errors.add("line " + lineNo + ": amount '" + rawAmount + "' is not a number");
                    continue;
                }
                if (amount.signum() <= 0) {
                    errors.add("line " + lineNo + ": amount must be positive");
                    continue;
                }
                if (amount.scale() > 2) {
                    errors.add("line " + lineNo + ": amount must have at most 2 decimal places");
                    continue;
                }
                if (iban.isBlank()) {
                    errors.add("line " + lineNo + ": IBAN is blank");
                    continue;
                }
                lines.add(new BankService.BatchLine("L" + (++dataRows), iban, name.isBlank() ? iban : name, amount));
            }
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Could not read the payroll file: " + ex.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "The payroll file has invalid lines and was not processed: " + String.join("; ", errors));
        }
        return lines;
    }

    /**
     * Split a single CSV record into fields per RFC 4180: fields are comma-separated; a field may be
     * wrapped in double quotes, inside which commas are literal and a doubled quote ("") is an escaped
     * quote. This keeps a comma-containing name or amount confined to its own column instead of leaking
     * into the next field. (We process one physical line, so embedded newlines inside quotes are out of
     * scope for this single-line upload format.)
     */
    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"'); // escaped quote
                        i++;
                    } else {
                        inQuotes = false; // closing quote
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
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

    /**
     * A failed {@code @RequestParam} bean-validation constraint (blank/negative/over-precision value
     * posted past the browser's HTML5 checks) throws HandlerMethodValidationException in Spring 6.1+,
     * NOT IllegalArgumentException — so without this handler it would render the generic Whitelabel
     * page instead of the branded Meridian error page.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @org.springframework.web.bind.annotation.ExceptionHandler({
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            jakarta.validation.ConstraintViolationException.class})
    public String handleValidation(Exception ex, HttpSession session, Model model) {
        model.addAttribute("customer", owner(session));
        model.addAttribute("navActive", "");
        model.addAttribute("ownerAccounts",
                owner(session) == null ? List.of() : bankService.accountsForOwner(owner(session)));
        model.addAttribute("errorTitle", "Invalid request");
        model.addAttribute("errorMessage", "Please check the form values and try again.");
        return "error-page";
    }

    /** A horizontal-authorization failure (operating on another customer's account) → HTTP 403. */
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @org.springframework.web.bind.annotation.ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, HttpSession session, Model model) {
        model.addAttribute("customer", owner(session));
        model.addAttribute("navActive", "");
        model.addAttribute("ownerAccounts",
                owner(session) == null ? List.of() : bankService.accountsForOwner(owner(session)));
        model.addAttribute("errorTitle", "Not allowed");
        model.addAttribute("errorMessage", "You don't have access to that account.");
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
