package com.cy.diakritis.bank.service;

import com.cy.diakritis.bank.repo.AccountRepository;
import com.cy.diakritis.bank.repo.PayeeRepository;
import com.cy.diakritis.bank.web.dto.AccountView;
import com.cy.diakritis.bank.web.dto.BatchItemRequest;
import com.cy.diakritis.bank.web.dto.BatchRequest;
import com.cy.diakritis.bank.web.dto.DepositBreakRequest;
import com.cy.diakritis.bank.web.dto.LimitChangeRequest;
import com.cy.diakritis.bank.web.dto.PayeeRequest;
import com.cy.diakritis.bank.web.dto.PayeeView;
import com.cy.diakritis.bank.web.dto.SessionRequest;
import com.cy.diakritis.bank.web.dto.TransferRequest;
import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.ActionPayload;
import com.cy.diakritis.common.dto.BatchItem;
import com.cy.diakritis.common.dto.BeneficiaryAddPayload;
import com.cy.diakritis.common.dto.Counterparty;
import com.cy.diakritis.common.dto.Decision;
import com.cy.diakritis.common.dto.DepositBreakPayload;
import com.cy.diakritis.common.dto.DeviceInfo;
import com.cy.diakritis.common.dto.EventType;
import com.cy.diakritis.common.dto.LimitChangePayload;
import com.cy.diakritis.common.dto.MassPaymentPayload;
import com.cy.diakritis.common.dto.SessionContext;
import com.cy.diakritis.common.dto.TransferPayload;
import com.cy.diakritis.common.persistence.AccountItem;
import com.cy.diakritis.common.persistence.PayeeItem;
import com.cy.diakritis.common.persistence.TermDeposit;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates customer banking actions: it loads the authoritative account/payee facts the bank
 * holds, assembles the corresponding {@link ActionEvent}, forwards it to decision-service, and
 * returns the {@link Decision}. The bank never decides locally; it only describes the action.
 */
@Service
public class BankingService {

    private static final String ACCOUNT_PK_PREFIX = "ACC#";
    private static final String PAYEE_SK_PREFIX = "PAYEE#";

    private final AccountRepository accountRepository;
    private final PayeeRepository payeeRepository;
    private final DecisionServiceClient decisionServiceClient;

    public BankingService(AccountRepository accountRepository,
                          PayeeRepository payeeRepository,
                          DecisionServiceClient decisionServiceClient) {
        this.accountRepository = accountRepository;
        this.payeeRepository = payeeRepository;
        this.decisionServiceClient = decisionServiceClient;
    }

    public AccountView getAccount(String accountId) {
        AccountItem item = requireAccount(accountId);
        return toAccountView(accountId, item);
    }

    public List<PayeeView> listPayees(String accountId) {
        List<PayeeView> views = new ArrayList<>();
        for (PayeeItem payee : payeeRepository.findByAccount(accountId)) {
            views.add(toPayeeView(payee));
        }
        return views;
    }

    public Decision transfer(String accountId, TransferRequest request, EventType eventType) {
        AccountItem account = requireAccount(accountId);
        Counterparty counterparty = resolveCounterparty(
                accountId,
                request.addressing(),
                request.value(),
                request.resolvedAccountRef(),
                null,
                null);
        TransferPayload payload = new TransferPayload(
                counterparty,
                request.amountEur(),
                MoneyConversions.centsToEur(account.getAvailableBalanceCents()),
                request.rail());
        ActionEvent event = buildEvent(accountId, eventType, payload, request.session());
        return decisionServiceClient.decide(event);
    }

    public Decision addPayee(String accountId, PayeeRequest request) {
        requireAccount(accountId);
        String counterpartyKey = CounterpartyKeys.of(
                request.addressing(), request.value(), request.resolvedAccountRef());
        Instant now = Instant.now();
        Counterparty counterparty = new Counterparty(
                request.addressing(),
                request.value(),
                request.resolvedAccountRef(),
                request.resolvedName(),
                request.displayName(),
                now);
        BeneficiaryAddPayload payload = new BeneficiaryAddPayload(counterparty);
        ActionEvent event = buildEvent(accountId, EventType.BENEFICIARY_ADD, payload, request.session());
        Decision decision = decisionServiceClient.decide(event);
        persistPayee(accountId, counterpartyKey, counterparty, request.session().sessionId(), now);
        return decision;
    }

    public Decision massPayment(String accountId, BatchRequest request) {
        AccountItem account = requireAccount(accountId);
        List<BatchItem> items = new ArrayList<>(request.items().size());
        BigDecimal total = BigDecimal.ZERO;
        for (BatchItemRequest itemRequest : request.items()) {
            Counterparty counterparty = resolveCounterparty(
                    accountId,
                    itemRequest.addressing(),
                    itemRequest.value(),
                    itemRequest.resolvedAccountRef(),
                    null,
                    null);
            items.add(new BatchItem(itemRequest.itemId(), counterparty, itemRequest.amountEur()));
            total = total.add(itemRequest.amountEur());
        }
        String batchId = (request.batchId() == null || request.batchId().isBlank())
                ? UUID.randomUUID().toString()
                : request.batchId();
        MassPaymentPayload payload = new MassPaymentPayload(
                batchId,
                request.purposeHint(),
                items,
                total,
                MoneyConversions.centsToEur(account.getAvailableBalanceCents()),
                request.rail());
        ActionEvent event = buildEvent(accountId, EventType.MASS_PAYMENT, payload, request.session());
        return decisionServiceClient.decide(event);
    }

    public Decision breakDeposit(String accountId, String depositId, DepositBreakRequest request) {
        AccountItem account = requireAccount(accountId);
        TermDeposit deposit = findDeposit(account, depositId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Term deposit " + depositId + " not found on account " + accountId));
        if (deposit.isBroken()) {
            throw new BadRequestException("Term deposit " + depositId + " is already broken");
        }
        DepositBreakPayload payload = new DepositBreakPayload(
                deposit.getDepositId(),
                MoneyConversions.centsToEur(deposit.getPrincipalCents()),
                Instant.ofEpochMilli(deposit.getMaturityEpochMs()),
                MoneyConversions.centsToEur(deposit.getPenaltyCents()));
        ActionEvent event = buildEvent(accountId, EventType.TERM_DEPOSIT_BREAK, payload, request.session());
        return decisionServiceClient.decide(event);
    }

    public Decision changeLimit(String accountId, LimitChangeRequest request) {
        requireAccount(accountId);
        LimitChangePayload payload = new LimitChangePayload(request.currentLimitEur(), request.newLimitEur());
        ActionEvent event = buildEvent(accountId, EventType.LIMIT_CHANGE, payload, request.session());
        return decisionServiceClient.decide(event);
    }

    private Counterparty resolveCounterparty(String accountId,
                                             com.cy.diakritis.common.dto.Addressing addressing,
                                             String value,
                                             String resolvedAccountRef,
                                             String resolvedNameOverride,
                                             String displayNameOverride) {
        String counterpartyKey = CounterpartyKeys.of(addressing, value, resolvedAccountRef);
        Optional<PayeeItem> saved = payeeRepository.findByAccountAndKey(accountId, counterpartyKey);
        String resolvedName = resolvedNameOverride;
        String displayName = displayNameOverride;
        Instant beneficiaryCreatedAt = null;
        if (saved.isPresent()) {
            PayeeItem payee = saved.get();
            if (resolvedName == null) {
                resolvedName = payee.getResolvedName();
            }
            if (displayName == null) {
                displayName = payee.getDisplayName();
            }
            beneficiaryCreatedAt = Instant.ofEpochMilli(payee.getCreatedEpochMs());
        }
        return new Counterparty(addressing, value, resolvedAccountRef, resolvedName, displayName, beneficiaryCreatedAt);
    }

    private void persistPayee(String accountId,
                              String counterpartyKey,
                              Counterparty counterparty,
                              String sessionId,
                              Instant createdAt) {
        PayeeItem payee = new PayeeItem();
        payee.setPk(ACCOUNT_PK_PREFIX + accountId);
        payee.setSk(PAYEE_SK_PREFIX + counterpartyKey);
        payee.setIban(counterparty.value());
        payee.setDisplayName(counterparty.displayName());
        payee.setResolvedName(counterparty.resolvedName());
        payee.setCreatedEpochMs(createdAt.toEpochMilli());
        payee.setAddedInSessionId(sessionId);
        payee.setSource("BERKA");
        payeeRepository.save(payee);
    }

    private ActionEvent buildEvent(String accountId,
                                   EventType eventType,
                                   ActionPayload payload,
                                   SessionRequest session) {
        SessionContext context = new SessionContext(
                Instant.now(),
                session.sessionId(),
                session.channel(),
                session.ip(),
                new DeviceInfo(session.deviceId(), session.platform()));
        return new ActionEvent(UUID.randomUUID().toString(), accountId, eventType, payload, context);
    }

    private AccountItem requireAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + accountId + " not found"));
    }

    private Optional<TermDeposit> findDeposit(AccountItem account, String depositId) {
        if (account.getTermDeposits() == null) {
            return Optional.empty();
        }
        return account.getTermDeposits().stream()
                .filter(deposit -> deposit.getDepositId().equals(depositId))
                .findFirst();
    }

    private AccountView toAccountView(String accountId, AccountItem item) {
        List<AccountView.TermDepositView> deposits = new ArrayList<>();
        if (item.getTermDeposits() != null) {
            for (TermDeposit deposit : item.getTermDeposits()) {
                deposits.add(new AccountView.TermDepositView(
                        deposit.getDepositId(),
                        MoneyConversions.centsToEur(deposit.getPrincipalCents()),
                        Instant.ofEpochMilli(deposit.getMaturityEpochMs()),
                        MoneyConversions.centsToEur(deposit.getPenaltyCents()),
                        deposit.isBroken()));
            }
        }
        return new AccountView(
                accountId,
                item.getDisplayName(),
                MoneyConversions.centsToEur(item.getAvailableBalanceCents()),
                item.isBusiness(),
                item.getApproverUserIds(),
                deposits.isEmpty() ? null : deposits,
                item.getSource());
    }

    private PayeeView toPayeeView(PayeeItem payee) {
        String counterpartyKey = payee.getSk() != null && payee.getSk().startsWith(PAYEE_SK_PREFIX)
                ? payee.getSk().substring(PAYEE_SK_PREFIX.length())
                : payee.getSk();
        return new PayeeView(
                counterpartyKey,
                payee.getIban(),
                payee.getDisplayName(),
                payee.getResolvedName(),
                payee.getCreatedEpochMs() > 0 ? Instant.ofEpochMilli(payee.getCreatedEpochMs()) : null,
                payee.getSource());
    }
}
