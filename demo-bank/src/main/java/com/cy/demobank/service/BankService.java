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
import com.cy.demobank.repo.AccountRepository;
import com.cy.demobank.repo.DepositRepository;
import com.cy.demobank.repo.PayeeRepository;
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
    private final DecisionClient decisionClient;

    public BankService(AccountRepository accountRepository,
                       PayeeRepository payeeRepository,
                       DepositRepository depositRepository,
                       DecisionClient decisionClient) {
        this.accountRepository = accountRepository;
        this.payeeRepository = payeeRepository;
        this.depositRepository = depositRepository;
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

    public List<Payee> payees(String accountId) {
        return payeeRepository.findByAccount(accountId);
    }

    public List<Deposit> deposits(String accountId) {
        return depositRepository.findByAccount(accountId);
    }

    // ------------------------------------------------------------------------------------------------
    // TRANSFER — to an existing established payee.
    // ------------------------------------------------------------------------------------------------

    /**
     * Score and (on ALLOW) execute a transfer from {@code accountId} to an existing payee.
     *
     * @param rail the payment rail (SEPA, INSTANT, INTERNAL, P2P).
     */
    public ActionResult transfer(String accountId, String cpKey, BigDecimal amountEur, String rail) {
        Account account = requireAccount(accountId);
        Payee payee = payeeRepository.find(accountId, cpKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payee " + cpKey + " for " + accountId));

        CounterpartyDto counterparty = new CounterpartyDto(
                ADDRESSING_IBAN, payee.iban(), payee.cpKey(), payee.resolvedName(), payee.displayName(),
                Instant.ofEpochMilli(payee.createdEpochMs()));

        return runTransfer("TRANSFER", account, counterparty, amountEur, rail,
                mobileContext(eventId("transfer")), "Transfer to " + payee.displayName());
    }

    // ------------------------------------------------------------------------------------------------
    // P2P transfer — to a phone-number (MSISDN) alias, resolved to a named person.
    // ------------------------------------------------------------------------------------------------

    public ActionResult p2p(String accountId, String alias, String resolvedName,
                            BigDecimal amountEur) {
        Account account = requireAccount(accountId);
        String resolvedRef = "p2p-ref-" + Integer.toHexString(alias.hashCode());
        CounterpartyDto counterparty = new CounterpartyDto(
                ADDRESSING_MSISDN, alias, resolvedRef, resolvedName, resolvedName, null);

        return runTransfer("P2P_TRANSFER", account, counterparty, amountEur, "P2P",
                mobileContext(eventId("p2p")), "P2P to " + resolvedName + " (" + alias + ")");
    }

    // ------------------------------------------------------------------------------------------------
    // Beneficiary add — score adding a new payee; on ALLOW persist it.
    // ------------------------------------------------------------------------------------------------

    public ActionResult addBeneficiary(String accountId, String iban, String displayName,
                                       String resolvedName) {
        Account account = requireAccount(accountId);
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
                    accountId, cpKey, iban, displayName, resolvedName, Instant.now().toEpochMilli()));
        }
        String message = allowed
                ? "Beneficiary '" + displayName + "' added."
                : "Beneficiary add not applied (verdict " + verdict + ").";
        return new ActionResult(decision, allowed, message, "BENEFICIARY_ADD");
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
    public ActionResult breakDeposit(String depositId) {
        Deposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown deposit: " + depositId));
        if (deposit.broken()) {
            throw new IllegalArgumentException("Deposit " + depositId + " is already broken");
        }
        Account account = requireAccount(deposit.accountId());

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
        return new ActionResult(decision, execute, message, "TERM_DEPOSIT_BREAK");
    }

    // ------------------------------------------------------------------------------------------------
    // Drain transfer to a NEW payee — the kill-chain's second leg (expected HOLD after a break).
    // ------------------------------------------------------------------------------------------------

    /**
     * Transfer to a brand-new IBAN (not an existing payee). Used as the kill-chain's drain leg: after
     * breaking the deposit this sweep to a never-seen counterparty trips the liquidation_kill_chain
     * typology → HOLD, and the balance is NOT applied.
     */
    public ActionResult transferToNew(String accountId, String iban, String resolvedName,
                                      BigDecimal amountEur, String rail) {
        Account account = requireAccount(accountId);
        CounterpartyDto counterparty = new CounterpartyDto(
                ADDRESSING_IBAN, iban, iban, resolvedName, resolvedName == null ? "Payee" : resolvedName, null);
        return runTransfer("TRANSFER", account, counterparty, amountEur, rail,
                mobileContext(eventId("drain")), "Transfer to new payee " + iban);
    }

    // ------------------------------------------------------------------------------------------------
    // Mass payment (batch) — business-style multi-line payout.
    // ------------------------------------------------------------------------------------------------

    public ActionResult massPayment(String accountId, List<BatchLine> lines, String rail) {
        Account account = requireAccount(accountId);
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
            long totalCents = total.movePointRight(2).longValueExact();
            if (!accountRepository.debitIfSufficient(account.id(), totalCents)) {
                return new ActionResult(decision, false,
                        "Batch allowed by Diakrisis but insufficient balance to execute.", "MASS_PAYMENT");
            }
        }
        String message = applied
                ? "Batch executed: " + total + " EUR across " + lines.size() + " lines."
                : "Batch not applied (verdict " + verdict + ").";
        return new ActionResult(decision, applied, message, "MASS_PAYMENT");
    }

    // ------------------------------------------------------------------------------------------------
    // Shared transfer execution path (TRANSFER / P2P_TRANSFER).
    // ------------------------------------------------------------------------------------------------

    private ActionResult runTransfer(String eventType, Account account, CounterpartyDto counterparty,
                                     BigDecimal amountEur, String rail, SessionContextDto context,
                                     String label) {
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
            long amountCents = amountEur.movePointRight(2).longValueExact();
            if (!accountRepository.debitIfSufficient(account.id(), amountCents)) {
                LOG.warn("ALLOW for {} but balance insufficient on {}", label, account.id());
                return new ActionResult(decision, false,
                        label + " allowed by Diakrisis but insufficient balance to execute.", eventType);
            }
        }
        String message = applied
                ? label + " executed: " + amountEur + " EUR debited from " + account.id() + "."
                : label + " not applied (verdict " + verdict + ").";
        return new ActionResult(decision, applied, message, eventType);
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
