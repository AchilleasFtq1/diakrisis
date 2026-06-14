package com.cy.demobank.service;

import com.cy.demobank.client.DecisionClient;
import com.cy.demobank.client.dto.ActionEventRequest;
import com.cy.demobank.client.dto.BatchItemDto;
import com.cy.demobank.client.dto.BeneficiaryAddPayloadDto;
import com.cy.demobank.client.dto.CounterpartyDto;
import com.cy.demobank.client.dto.DecisionResponse;
import com.cy.demobank.client.dto.DepositBreakPayloadDto;
import com.cy.demobank.client.dto.DeviceDto;
import com.cy.demobank.client.dto.MassPaymentPayloadDto;
import com.cy.demobank.client.dto.SessionContextDto;
import com.cy.demobank.client.dto.TransferPayloadDto;
import com.cy.demobank.domain.Account;
import com.cy.demobank.domain.Deposit;
import com.cy.demobank.domain.Payee;
import com.cy.demobank.domain.Txn;
import com.cy.demobank.repo.AccountRepository;
import com.cy.demobank.repo.DepositRepository;
import com.cy.demobank.repo.PayeeRepository;
import com.cy.demobank.repo.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The demo-bank's money-movement service. Every action follows the same arc: read the SQLite account
 * facts, build the snake_case {@code ActionEvent}, ask Diakrisis to score it (logging in for a JWT
 * via {@link DecisionClient}), and apply the balance change ONLY on an ALLOW verdict. Every other
 * verdict (CONFIRM/HOLD/BLOCK/REQUIRE_APPROVAL) is rendered with its explanation and leaves the
 * account untouched — the friction the Diakrisis engine intends.
 */
@Service
public class BankService {

    private static final Logger LOG = LoggerFactory.getLogger(BankService.class);

    private static final String VERDICT_ALLOW = "ALLOW";
    private static final String CHANNEL_WEB = "WEB";
    private static final String CHANNEL_MOBILE = "MOBILE_APP";
    private static final String DEVICE_ID = "dev-1";
    private static final String PLATFORM_IOS = "IOS";
    private static final String HOME_IP = "203.0.113.7";
    private static final String ADDRESSING_IBAN = "IBAN";
    private static final String ADDRESSING_MSISDN = "MSISDN";

    private final AccountRepository accountRepository;
    private final PayeeRepository payeeRepository;
    private final DepositRepository depositRepository;
    private final TransactionRepository transactionRepository;
    private final DecisionClient decisionClient;

    public BankService(AccountRepository accountRepository,
                       PayeeRepository payeeRepository,
                       DepositRepository depositRepository,
                       TransactionRepository transactionRepository,
                       DecisionClient decisionClient) {
        this.accountRepository = accountRepository;
        this.payeeRepository = payeeRepository;
        this.depositRepository = depositRepository;
        this.transactionRepository = transactionRepository;
        this.decisionClient = decisionClient;
    }

    // ------------------------------------------------------------------------------------------------
    // Read paths used by the controllers.
    // ------------------------------------------------------------------------------------------------

    public List<Account> accounts() {
        return accountRepository.findAll();
    }

    public Account requireAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountId));
    }

    /**
     * Resolve an account and assert the given customer owns it. This is the service-layer authorization
     * boundary for every per-account read/mutation: it rejects horizontal access (customer-A operating
     * on customer-B's account) regardless of caller, so ownership is enforced even if a controller path
     * forgets to check. A missing account is a 400 (bad input); a wrong-owner account is a 403.
     *
     * @throws IllegalArgumentException if the account does not exist.
     * @throws AccessDeniedException    if it exists but is not owned by {@code ownerUser}.
     */
    public Account requireOwnedAccount(String accountId, String ownerUser) {
        Account account = requireAccount(accountId);
        if (ownerUser == null || !ownerUser.equals(account.ownerUser())) {
            throw new AccessDeniedException("Account " + accountId + " is not accessible to the signed-in customer.");
        }
        return account;
    }

    /** The full statement for an account the given customer owns (newest first). */
    public List<Txn> statement(String accountId, String ownerUser) {
        requireOwnedAccount(accountId, ownerUser);
        return statement(accountId);
    }

    /** The saved payees of an account the given customer owns. */
    public List<Payee> payeesForOwner(String accountId, String ownerUser) {
        requireOwnedAccount(accountId, ownerUser);
        return payees(accountId);
    }

    /**
     * Convert a euro amount to integer cents, rejecting any sub-cent (scale &gt; 2) value as a clean
     * validation error rather than letting {@link BigDecimal#longValueExact()} throw an uncaught
     * {@link ArithmeticException} (which surfaces as an HTTP 500). We deliberately do NOT round —
     * silently altering the customer's stated amount would be wrong; we reject and let the caller fix it.
     */
    private static long toCents(BigDecimal amountEur) {
        if (amountEur.scale() > 2) {
            throw new IllegalArgumentException("Amount must have at most 2 decimal places.");
        }
        return amountEur.movePointRight(2).longValueExact();
    }

    public List<Payee> payees(String accountId) {
        return payeeRepository.findByAccount(accountId);
    }

    public List<Deposit> deposits(String accountId) {
        return depositRepository.findByAccount(accountId);
    }

    /** The accounts a given customer (owner) holds. */
    public List<Account> accountsForOwner(String ownerUser) {
        if (ownerUser == null) {
            return List.of();
        }
        return accountRepository.findAll().stream()
                .filter(a -> ownerUser.equals(a.ownerUser()))
                .toList();
    }

    /** True if {@code ownerUser} is the holder of at least one real account (a valid sign-in identity). */
    public boolean isKnownOwner(String ownerUser) {
        return !accountsForOwner(ownerUser).isEmpty();
    }

    /** The full statement for an account (newest first). */
    public List<Txn> statement(String accountId) {
        return transactionRepository.findByAccount(accountId);
    }

    /** All activity for a customer across their accounts (newest first). */
    public List<Txn> activity(String ownerUser) {
        return transactionRepository.findByOwner(ownerUser);
    }

    /** The most recent actions for a customer (dashboard preview). */
    public List<Txn> recentActivity(String ownerUser, int limit) {
        return transactionRepository.recentByOwner(ownerUser, limit);
    }

    // ------------------------------------------------------------------------------------------------
    // Lifecycle — the customer confirms (step-up) or cancels a pending payment.
    // ------------------------------------------------------------------------------------------------

    /**
     * Complete a CONFIRM step-up for the customer {@code ownerUser}: drive the decision-service
     * lifecycle ({@code /actions/{id}/confirm}), debit the account, and mark the pending transaction as
     * Sent. The amount and account that are committed are taken from the originally scored pending
     * transaction (recovered by {@code eventId}) — never from the client — so an attacker cannot confirm
     * a high-risk event while submitting a smaller (or unrelated) amount, nor replay another customer's
     * event id against their own account.
     *
     * <p>The debit result is honored: if the balance is insufficient the transaction is NOT marked Sent;
     * it is marked Cancelled and a non-applied result is returned, mirroring {@link #runTransfer} and
     * {@link #massPayment}, so {@code applied=1} can never be set without an actual debit.
     *
     * @param submittedAmountEur the amount echoed back by the step-up form; used only to assert it equals
     *                           the stored pending amount, not as the source of truth for the debit.
     * @throws AccessDeniedException    if {@code accountId} is not owned by {@code ownerUser}, or the
     *                                  pending transaction belongs to a different account/owner.
     * @throws IllegalArgumentException if no pending transaction exists for {@code eventId}, or the
     *                                  submitted amount does not match the stored amount.
     */
    public ActionResult confirmPayment(String ownerUser, String accountId, String eventId, BigDecimal submittedAmountEur) {
        Account account = requireOwnedAccount(accountId, ownerUser);

        // Recover the originally scored pending payment. The stored row is the single source of truth
        // for the amount and the target account; the client's posted amount is only cross-checked.
        Txn pending = transactionRepository.findByEventId(eventId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No payment is awaiting confirmation for this reference."));
        if (pending.statusOverride() != null && !pending.statusOverride().isBlank()) {
            throw new IllegalArgumentException("This payment has already been resolved.");
        }
        if (!account.id().equals(pending.accountId()) || !ownerUser.equals(pending.ownerUser())) {
            throw new AccessDeniedException("This payment does not belong to the signed-in account.");
        }
        long storedCents = pending.amountCents();
        if (submittedAmountEur != null && toCents(submittedAmountEur) != storedCents) {
            throw new IllegalArgumentException("The confirmed amount does not match the payment we scored.");
        }

        decisionClient.act(account.ownerUser(), eventId, "confirm");

        if (storedCents > 0) {
            boolean debited = accountRepository.debitIfSufficient(account.id(), storedCents);
            if (!debited) {
                LOG.warn("CONFIRM for event {} but balance insufficient on {}", eventId, account.id());
                transactionRepository.markStatus(eventId, "Cancelled");
                return new ActionResult(null, false,
                        "Payment confirmed but there was not enough balance to send it.", "TRANSFER");
            }
        }
        transactionRepository.markStatus(eventId, "Sent");
        return new ActionResult(null, true, "Payment confirmed and sent.", "TRANSFER");
    }

    /** Cancel a held/pending payment owned by {@code ownerUser}: drive {@code /actions/{id}/cancel}
     * and mark it Cancelled. The pending transaction must belong to the owned account. */
    public void cancelPayment(String ownerUser, String accountId, String eventId) {
        Account account = requireOwnedAccount(accountId, ownerUser);
        Txn pending = transactionRepository.findByEventId(eventId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No payment is awaiting cancellation for this reference."));
        if (pending.statusOverride() != null && !pending.statusOverride().isBlank()) {
            throw new IllegalArgumentException("This payment has already been resolved.");
        }
        if (!account.id().equals(pending.accountId()) || !ownerUser.equals(pending.ownerUser())) {
            throw new AccessDeniedException("This payment does not belong to the signed-in account.");
        }
        decisionClient.act(account.ownerUser(), eventId, "cancel");
        transactionRepository.markStatus(eventId, "Cancelled");
    }

    /** Persist one action + its live verdict to the statement/activity ledger. */
    private void record(Account account, String kind, String cpName, String cpRef,
                        BigDecimal amountEur, String rail, String reference, ActionResult result) {
        DecisionResponse d = result.decision();
        String scam = result.typologies().isEmpty() ? null : String.join(", ", result.typologies());
        long cents = amountEur == null ? 0L : toCents(amountEur);
        transactionRepository.insert(new Txn(
                UUID.randomUUID().toString(),
                d == null ? null : d.eventId(), null,
                account.id(), account.ownerUser(), kind, cpName, cpRef,
                blankToNull(reference), cents, rail, result.verdict(),
                d == null ? null : d.friction(),
                d == null ? null : d.reasonCode(),
                scam, result.applied(), Instant.now().toEpochMilli()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ------------------------------------------------------------------------------------------------
    // TRANSFER — to an existing established payee.
    // ------------------------------------------------------------------------------------------------

    /**
     * Score and (on ALLOW) execute a transfer from {@code accountId} to an existing payee. The account
     * must be owned by {@code ownerUser}.
     *
     * @param rail the payment rail (SEPA, INSTANT, INTERNAL, P2P).
     */
    public ActionResult transfer(String ownerUser, String accountId, String cpKey, BigDecimal amountEur, String rail) {
        return transfer(ownerUser, accountId, cpKey, amountEur, rail, null);
    }

    public ActionResult transfer(String ownerUser, String accountId, String cpKey, BigDecimal amountEur, String rail, String reference) {
        Account account = requireOwnedAccount(accountId, ownerUser);
        Payee payee = payeeRepository.find(accountId, cpKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payee " + cpKey + " for " + accountId));

        // The engine reads its established-payee baseline under the counterparty KEY (cpKey, e.g.
        // "CD|46939146"), so that is what we send as counterparty.value. The payee's IBAN is a
        // display-only banking detail (shown on the form + receipt) and is not scored.
        CounterpartyDto counterparty = new CounterpartyDto(
                ADDRESSING_IBAN, payee.cpKey(), payee.cpKey(), payee.resolvedName(), payee.displayName(),
                Instant.ofEpochMilli(payee.createdEpochMs()));

        return runTransfer("TRANSFER", account, counterparty, amountEur, rail,
                mobileContext(eventId("transfer")), "Transfer to " + payee.displayName(),
                reference);
    }

    // ------------------------------------------------------------------------------------------------
    // P2P transfer — to a phone-number (MSISDN) alias, resolved to a named person.
    // ------------------------------------------------------------------------------------------------

    public ActionResult p2p(String ownerUser, String accountId, String alias, String resolvedName,
                            BigDecimal amountEur) {
        Account account = requireOwnedAccount(accountId, ownerUser);
        String resolvedRef = "p2p-ref-" + Integer.toHexString(alias.hashCode());
        CounterpartyDto counterparty = new CounterpartyDto(
                ADDRESSING_MSISDN, alias, resolvedRef, resolvedName, resolvedName, null);

        return runTransfer("P2P_TRANSFER", account, counterparty, amountEur, "P2P",
                mobileContext(eventId("p2p")), "P2P to " + resolvedName + " (" + alias + ")", null);
    }

    // ------------------------------------------------------------------------------------------------
    // Beneficiary add — score adding a new payee; on ALLOW persist it.
    // ------------------------------------------------------------------------------------------------

    public ActionResult addBeneficiary(String ownerUser, String accountId, String iban, String displayName,
                                       String resolvedName) {
        return addBeneficiary(ownerUser, accountId, iban, null, displayName, resolvedName);
    }

    public ActionResult addBeneficiary(String ownerUser, String accountId, String iban, String bic, String displayName,
                                       String resolvedName) {
        Account account = requireOwnedAccount(accountId, ownerUser);
        String cpKey = iban; // For the demo the counterparty key IS the IBAN/addressing value.
        if (payeeRepository.exists(accountId, cpKey)) {
            throw new IllegalArgumentException("Payee " + cpKey + " already exists for " + accountId);
        }
        CounterpartyDto counterparty = new CounterpartyDto(
                ADDRESSING_IBAN, iban, cpKey, resolvedName, displayName, null);

        ActionEventRequest event = new ActionEventRequest(
                eventId("benef"), accountId, "BENEFICIARY_ADD",
                new BeneficiaryAddPayloadDto(counterparty), webContext(eventId("benef-ctx")));

        DecisionResponse decision = decisionClient.decide(account.ownerUser(), event);
        String verdict = decision.effectiveDecision();
        boolean allowed = VERDICT_ALLOW.equals(verdict);
        if (allowed) {
            payeeRepository.insert(new Payee(
                    accountId, cpKey, iban, bic, displayName, resolvedName, Instant.now().toEpochMilli()));
        }
        String message = allowed
                ? "Beneficiary '" + displayName + "' added."
                : "Beneficiary add not applied (verdict " + verdict + ").";
        ActionResult result = new ActionResult(decision, allowed, message, "BENEFICIARY_ADD");
        record(account, "PAYEE_ADD", displayName, iban, BigDecimal.ZERO, null, null, result);
        return result;
    }

    // ------------------------------------------------------------------------------------------------
    // Term-deposit break — the kill-chain's first leg.
    // ------------------------------------------------------------------------------------------------

    /**
     * Score breaking a term deposit and, on confirmation, mark it broken. Diakrisis returns CONFIRM
     * for a normal break (never HOLD/BLOCK) and records the freed-funds posture that a follow-on drain
     * transfer trips — the liquidation kill-chain.
     *
     * <p>The freed principal is NOT immediately credited to the spendable available balance: in a real
     * bank a broken-deposit settles rather than landing instantly. Keeping the available balance at its
     * pre-break value is also what makes the kill-chain's follow-on sweep a genuine liquidation of the
     * account (the decision engine's drain signal is calibrated against the real available balance), so
     * the second leg correctly trips {@code liquidation_kill_chain}.
     */
    public ActionResult breakDeposit(String ownerUser, String depositId) {
        Deposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown deposit: " + depositId));
        if (deposit.broken()) {
            throw new IllegalArgumentException("Deposit " + depositId + " is already broken");
        }
        // Resolve the owning account and assert the caller owns it — a deposit can only be broken by
        // the customer who holds the account it sits against.
        Account account = requireOwnedAccount(deposit.accountId(), ownerUser);

        DepositBreakPayloadDto payload = new DepositBreakPayloadDto(
                deposit.id(), deposit.principalEur(),
                Instant.ofEpochMilli(deposit.maturityEpochMs()), deposit.penaltyEur());

        ActionEventRequest event = new ActionEventRequest(
                eventId("break"), account.id(), "TERM_DEPOSIT_BREAK", payload,
                mobileContext(eventId("break-ctx")));

        DecisionResponse decision = decisionClient.decide(account.ownerUser(), event);
        String verdict = decision.effectiveDecision();
        // A CONFIRM is the expected normal-break verdict; for the demo we treat ALLOW or CONFIRM as
        // "the customer confirmed", which commits the break (and the engine's freed-funds posture).
        boolean execute = VERDICT_ALLOW.equals(verdict) || "CONFIRM".equals(verdict);
        if (execute) {
            depositRepository.markBroken(deposit.id());
        }
        String message = execute
                ? "Deposit " + depositId + " broken; "
                    + deposit.principalEur().subtract(deposit.penaltyEur())
                    + " EUR freed (settling) for " + account.id() + "."
                : "Deposit break not applied (verdict " + verdict + ").";
        ActionResult result = new ActionResult(decision, execute, message, "TERM_DEPOSIT_BREAK");
        record(account, "DEPOSIT_BREAK", "Term deposit " + deposit.id(), deposit.id(),
                deposit.principalEur(), null, null, result);
        return result;
    }

    // ------------------------------------------------------------------------------------------------
    // Drain transfer to a NEW payee — the kill-chain's second leg (expected HOLD after a break).
    // ------------------------------------------------------------------------------------------------

    /**
     * Transfer to a brand-new IBAN (not an existing payee). Used as the kill-chain's drain leg: after
     * breaking the deposit this sweep to a never-seen counterparty trips the liquidation_kill_chain
     * typology → HOLD, and the balance is NOT applied.
     */
    public ActionResult transferToNew(String ownerUser, String accountId, String iban, String resolvedName,
                                      BigDecimal amountEur, String rail) {
        return transferToNew(ownerUser, accountId, iban, resolvedName, amountEur, rail, null);
    }

    public ActionResult transferToNew(String ownerUser, String accountId, String iban, String resolvedName,
                                      BigDecimal amountEur, String rail, String reference) {
        Account account = requireOwnedAccount(accountId, ownerUser);
        CounterpartyDto counterparty = new CounterpartyDto(
                ADDRESSING_IBAN, iban, iban, resolvedName, resolvedName == null ? "Payee" : resolvedName, null);
        return runTransfer("TRANSFER", account, counterparty, amountEur, rail,
                mobileContext(eventId("drain")), "Transfer to new payee " + iban, reference);
    }

    // ------------------------------------------------------------------------------------------------
    // Mass payment (batch) — business-style multi-line payout.
    // ------------------------------------------------------------------------------------------------

    public ActionResult massPayment(String ownerUser, String accountId, List<BatchLine> lines, String rail) {
        Account account = requireOwnedAccount(accountId, ownerUser);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A mass payment needs at least one line");
        }
        List<BatchItemDto> items = lines.stream()
                .map(line -> new BatchItemDto(
                        line.itemId(),
                        new CounterpartyDto(ADDRESSING_IBAN, line.iban(), line.iban(),
                                line.resolvedName(), line.resolvedName(), null),
                        line.amountEur()))
                .toList();
        BigDecimal total = lines.stream()
                .map(BatchLine::amountEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        MassPaymentPayloadDto payload = new MassPaymentPayloadDto(
                eventId("batch"), "demo-batch", items, total, account.availableBalanceEur(), rail);

        ActionEventRequest event = new ActionEventRequest(
                eventId("masspay"), account.id(), "MASS_PAYMENT", payload, webContext(eventId("masspay-ctx")));

        DecisionResponse decision = decisionClient.decide(account.ownerUser(), event);
        String verdict = decision.effectiveDecision();
        boolean applied = VERDICT_ALLOW.equals(verdict);
        if (applied) {
            long totalCents = toCents(total);
            if (!accountRepository.debitIfSufficient(account.id(), totalCents)) {
                return new ActionResult(decision, false,
                        "Batch allowed by Diakrisis but insufficient balance to execute.", "MASS_PAYMENT");
            }
        }
        String message = applied
                ? "Batch executed: " + total + " EUR across " + lines.size() + " lines."
                : "Batch not applied (verdict " + verdict + ").";
        ActionResult result = new ActionResult(decision, applied, message, "MASS_PAYMENT");
        record(account, "PAYROLL", "Payroll — " + lines.size() + " line(s)", null, total, rail, "Salary run", result);
        return result;
    }

    // ------------------------------------------------------------------------------------------------
    // Shared transfer execution path (TRANSFER / P2P_TRANSFER).
    // ------------------------------------------------------------------------------------------------

    private ActionResult runTransfer(String eventType, Account account, CounterpartyDto counterparty,
                                     BigDecimal amountEur, String rail, SessionContextDto context,
                                     String label, String reference) {
        if (amountEur == null || amountEur.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        TransferPayloadDto payload = new TransferPayloadDto(
                counterparty, amountEur, account.availableBalanceEur(), rail);

        ActionEventRequest event = new ActionEventRequest(
                eventId(eventType.toLowerCase()), account.id(), eventType, payload, context);

        DecisionResponse decision = decisionClient.decide(account.ownerUser(), event);
        String verdict = decision.effectiveDecision();
        boolean applied = VERDICT_ALLOW.equals(verdict);
        if (applied) {
            long amountCents = toCents(amountEur);
            if (!accountRepository.debitIfSufficient(account.id(), amountCents)) {
                LOG.warn("ALLOW for {} but balance insufficient on {}", label, account.id());
                return new ActionResult(decision, false,
                        label + " allowed by Diakrisis but insufficient balance to execute.", eventType);
            }
        }
        String message = applied
                ? label + " executed: " + amountEur + " EUR debited from " + account.id() + "."
                : label + " not applied (verdict " + verdict + ").";
        ActionResult result = new ActionResult(decision, applied, message, eventType);
        record(account, "P2P_TRANSFER".equals(eventType) ? "P2P" : "TRANSFER",
                counterparty.displayName(), counterparty.value(), amountEur, rail, reference, result);
        return result;
    }

    // ------------------------------------------------------------------------------------------------
    // Context + id helpers.
    // ------------------------------------------------------------------------------------------------

    private SessionContextDto mobileContext(String sessionId) {
        return new SessionContextDto(
                Instant.now(), sessionId, CHANNEL_MOBILE, HOME_IP,
                new DeviceDto(DEVICE_ID, PLATFORM_IOS));
    }

    private SessionContextDto webContext(String sessionId) {
        return new SessionContextDto(
                Instant.now(), sessionId, CHANNEL_WEB, HOME_IP,
                new DeviceDto(DEVICE_ID, "WEB"));
    }

    private String eventId(String prefix) {
        // A fresh id per action so the decision-service never short-circuits on idempotent replay
        // (which would skip the posture/reputation commits the kill-chain depends on).
        return prefix + "-" + UUID.randomUUID();
    }

    /** A single line of a mass payment as posted from the batch form / REST. */
    public record BatchLine(String itemId, String iban, String resolvedName, BigDecimal amountEur) {
    }
}
