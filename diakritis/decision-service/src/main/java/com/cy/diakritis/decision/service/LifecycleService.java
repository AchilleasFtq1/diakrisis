package com.cy.diakritis.decision.service;

import com.cy.diakritis.common.dto.Decision;
import com.cy.diakritis.common.dto.LifecycleState;
import com.cy.diakritis.common.dto.Outcome;
import com.cy.diakritis.common.dto.Signal;
import com.cy.diakritis.common.persistence.CaseItem;
import com.cy.diakritis.common.persistence.DecisionItem;
import com.cy.diakritis.common.persistence.OutcomeItem;
import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.common.security.Role;
import com.cy.diakritis.decision.repo.CaseRepository;
import com.cy.diakritis.decision.repo.DecisionRepository;
import com.cy.diakritis.decision.repo.OutcomeRepository;
import com.cy.diakritis.decision.web.error.ConflictException;
import com.cy.diakritis.decision.web.error.ForbiddenException;
import com.cy.diakritis.decision.web.error.NotFoundException;
import com.cy.diakritis.decision.web.error.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

/**
 * Drives the post-decision lifecycle transitions over the {@code Decisions} and {@code Cases}
 * records.
 *
 * <p>Guards (contractual):
 * <ul>
 *   <li><b>Four-eyes</b>: {@code approve} requires an {@code APPROVER} principal whose subject is not
 *       the stored initiator, else {@code 403 SELF_APPROVAL_FORBIDDEN}.</li>
 *   <li><b>Pre-expiry lock</b>: {@code release} before the recorded hold expiry is
 *       {@code 409 LOCKED_PRE_EXPIRY}; the customer-protection hold cannot be skipped early.</li>
 * </ul>
 */
@Service
public class LifecycleService {

    public static final String ERR_SELF_APPROVAL = "SELF_APPROVAL_FORBIDDEN";
    public static final String ERR_NOT_AUTHORISED = "APPROVER_OR_ADMIN_REQUIRED";
    public static final String ERR_LOCKED_PRE_EXPIRY = "LOCKED_PRE_EXPIRY";
    public static final String ERR_ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleService.class);

    /** Joins the firing signal ids into the recorded outcome's {@code signalPattern}. */
    private static final String SIGNAL_PATTERN_SEPARATOR = ",";

    private final DecisionRepository decisionRepository;
    private final CaseRepository caseRepository;
    private final OutcomeRepository outcomeRepository;
    private final JsonMapper jsonMapper;

    public LifecycleService(DecisionRepository decisionRepository, CaseRepository caseRepository,
                            OutcomeRepository outcomeRepository, JsonMapper jsonMapper) {
        this.decisionRepository = decisionRepository;
        this.caseRepository = caseRepository;
        this.outcomeRepository = outcomeRepository;
        this.jsonMapper = jsonMapper;
    }

    /** Customer confirms a CONFIRM-banded action → executed. */
    public LifecycleResult confirm(String eventId) {
        DecisionItem decision = require(eventId);
        requireState(decision, LifecycleState.PENDING_CONFIRM);
        return transition(decision, LifecycleState.EXECUTED);
    }

    /**
     * Customer abandons a held / pending action → cancelled. Cancelling a HELD action records a
     * §9.5 {@link Outcome#CONFIRMED_SAVE}: the cooling-off hold was a true catch — the customer
     * agreed the interrupted payment was wrong and abandoned it.
     */
    public LifecycleResult cancel(String eventId) {
        DecisionItem decision = require(eventId);
        requireOpen(decision);
        boolean wasHeld = LifecycleState.HELD.name().equals(decision.getLifecycleState());
        LifecycleResult result = transition(decision, LifecycleState.CANCELLED);
        if (wasHeld) {
            recordOutcome(decision, Outcome.CONFIRMED_SAVE);
        }
        return result;
    }

    /**
     * Release a held action after its hold has expired → executed. Before the expiry the action is
     * locked ({@code 409 LOCKED_PRE_EXPIRY}); the cooling-off hold cannot be skipped.
     */
    public LifecycleResult release(String eventId) {
        DecisionItem decision = require(eventId);
        requireState(decision, LifecycleState.HELD);
        long now = Instant.now().toEpochMilli();
        if (decision.getHoldExpiresEpochMs() > 0 && now < decision.getHoldExpiresEpochMs()) {
            throw new ConflictException(ERR_LOCKED_PRE_EXPIRY,
                    "Hold is locked until " + Instant.ofEpochMilli(decision.getHoldExpiresEpochMs()));
        }
        // A HELD action released after its hold expired and executed unchanged is a §9.5
        // FALSE_POSITIVE: the hold interrupted a legitimate payment (a friction cost, not a catch).
        LifecycleResult result = transition(decision, LifecycleState.EXECUTED);
        recordOutcome(decision, Outcome.FALSE_POSITIVE);
        return result;
    }

    /**
     * Designated approver signs off a REQUIRE_APPROVAL action → executed. Enforces four-eyes: the
     * approver must hold the {@code APPROVER} role and must not be the action's initiator.
     */
    public LifecycleResult approve(String eventId, AuthPrincipal principal) {
        DecisionItem decision = require(eventId);
        requireState(decision, LifecycleState.PENDING_APPROVAL);
        requireApprover(principal);
        if (isSelfApproval(principal, decision)) {
            throw new ForbiddenException(ERR_SELF_APPROVAL,
                    "An action cannot be approved by its initiator");
        }
        CaseItem caseItem = caseRepository.findByEventId(eventId).orElse(null);
        LifecycleResult result = transitionWithApprover(decision, LifecycleState.EXECUTED, principal, caseItem);
        // The approver judged the four-eyes action legitimate → a labelled APPROVED outcome.
        recordOutcome(decision, Outcome.APPROVED);
        return result;
    }

    /** Designated approver rejects a REQUIRE_APPROVAL action → rejected. */
    public LifecycleResult reject(String eventId, AuthPrincipal principal) {
        DecisionItem decision = require(eventId);
        requireState(decision, LifecycleState.PENDING_APPROVAL);
        requireApprover(principal);
        if (isSelfApproval(principal, decision)) {
            throw new ForbiddenException(ERR_SELF_APPROVAL,
                    "An action cannot be rejected by its initiator");
        }
        CaseItem caseItem = caseRepository.findByEventId(eventId).orElse(null);
        LifecycleResult result = transitionWithApprover(decision, LifecycleState.REJECTED, principal, caseItem);
        // The approver judged the four-eyes action fraudulent / unauthorised → a labelled REJECTED outcome.
        recordOutcome(decision, Outcome.REJECTED);
        return result;
    }

    // --- §9.5 feedback loop: decisions → outcomes → calibration -------------------------------

    /**
     * Record a labelled {@link Outcome} for a decided event — the persisted SDD §9.5 training signal.
     * The firing signal pattern (the ids of the signals that actually contributed to the original
     * verdict) is logged with the outcome so the {@code decisions → outcomes → calibration} loop is
     * concrete: a calibration pass can read which signal combinations led to true catches versus
     * false positives. The row is idempotent (pk = {@code OUTCOME#<eventId>}); a replayed transition
     * overwrites with the same label rather than double-counting.
     *
     * <p>Recording an outcome must never break a lifecycle transition that has already committed, so
     * a malformed stored response (signal pattern unreadable) degrades to an empty pattern and is
     * logged at WARN rather than propagated.
     */
    private void recordOutcome(DecisionItem decision, Outcome outcome) {
        String signalPattern = firingSignalPattern(decision);
        long now = Instant.now().toEpochMilli();

        OutcomeItem item = new OutcomeItem();
        item.setPk(OutcomeRepository.partitionKeyFor(decision.getEventId()));
        item.setSk(OutcomeRepository.sortKey());
        item.setEventId(decision.getEventId());
        item.setAccountId(decision.getAccountId());
        item.setOutcome(outcome.name());
        item.setSignalPattern(signalPattern);
        item.setAmountCents(decision.getAmountCents());
        item.setEpochMs(now);
        outcomeRepository.save(item);

        LOG.info("Outcome {} recorded for event {} (signal pattern: [{}])",
                outcome, decision.getEventId(), signalPattern);
    }

    /**
     * The firing signal pattern of a stored decision: the comma-joined ids of the engine signals that
     * actually contributed to the verdict (non-zero contribution), preserving the engine's signal
     * order. Empty when the response carries no signals or cannot be parsed.
     */
    private String firingSignalPattern(DecisionItem decision) {
        String responseJson = decision.getResponseJson();
        if (responseJson == null || responseJson.isBlank()) {
            return "";
        }
        try {
            Decision stored = jsonMapper.readValue(responseJson, Decision.class);
            if (stored.engineVerdict() == null || stored.engineVerdict().signals() == null) {
                return "";
            }
            StringBuilder pattern = new StringBuilder();
            for (Signal signal : stored.engineVerdict().signals()) {
                if (signal.contribution() != 0.0) {
                    if (pattern.length() > 0) {
                        pattern.append(SIGNAL_PATTERN_SEPARATOR);
                    }
                    pattern.append(signal.id());
                }
            }
            return pattern.toString();
        } catch (JacksonException ex) {
            LOG.warn("Could not parse stored decision for event {} to derive signal pattern: {}",
                    decision.getEventId(), ex.toString());
            return "";
        }
    }

    // --- internals ----------------------------------------------------------------------------

    private LifecycleResult transition(DecisionItem decision, LifecycleState target) {
        decision.setLifecycleState(target.name());
        decisionRepository.updateLifecycle(decision);
        CaseItem caseItem = caseRepository.findByEventId(decision.getEventId()).orElse(null);
        if (caseItem != null) {
            caseItem.setState(target.name());
            caseRepository.save(caseItem);
        }
        return resultFor(decision, target, caseItem);
    }

    private LifecycleResult transitionWithApprover(DecisionItem decision, LifecycleState target,
                                                   AuthPrincipal principal, CaseItem caseItem) {
        decision.setLifecycleState(target.name());
        decisionRepository.updateLifecycle(decision);
        if (caseItem != null) {
            caseItem.setState(target.name());
            caseItem.setApproverUserId(principal.userId());
            caseRepository.save(caseItem);
        }
        return resultFor(decision, target, caseItem);
    }

    /**
     * Build the transition result. For a batch case (mass-payment) on approval the CLEAN lines
     * execute while the quarantined lines stay HELD, per the {@code {items_executed, items_held}}
     * contract (§8.2 / §11.13): the redirected line is never released by the same approval that lets
     * the rest of the payroll run. On rejection / cancellation nothing executes and every line is
     * reported held.
     */
    private LifecycleResult resultFor(DecisionItem decision, LifecycleState target, CaseItem caseItem) {
        if (isBatchCase(caseItem)) {
            List<String> held = caseItem.getBatchHeldItemIds() == null
                    ? List.of() : List.copyOf(caseItem.getBatchHeldItemIds());
            List<String> clean = caseItem.getBatchCleanItemIds() == null
                    ? List.of() : List.copyOf(caseItem.getBatchCleanItemIds());
            if (target == LifecycleState.EXECUTED) {
                // Approval: the clean lines execute; the quarantined lines remain held.
                return LifecycleResult.batch(decision.getEventId(), target, clean, held);
            }
            if (target == LifecycleState.REJECTED || target == LifecycleState.CANCELLED) {
                List<String> all = new java.util.ArrayList<>(clean);
                all.addAll(held);
                return LifecycleResult.batch(decision.getEventId(), target, List.of(), all);
            }
        }
        return LifecycleResult.of(decision.getEventId(), target);
    }

    private static boolean isBatchCase(CaseItem caseItem) {
        if (caseItem == null) {
            return false;
        }
        boolean hasHeld = caseItem.getBatchHeldItemIds() != null && !caseItem.getBatchHeldItemIds().isEmpty();
        boolean hasClean = caseItem.getBatchCleanItemIds() != null && !caseItem.getBatchCleanItemIds().isEmpty();
        return hasHeld || hasClean;
    }

    private DecisionItem require(String eventId) {
        return decisionRepository.findByEventId(eventId)
                .orElseThrow(() -> new NotFoundException("No decision for event " + eventId));
    }

    private static void requireState(DecisionItem decision, LifecycleState expected) {
        if (!expected.name().equals(decision.getLifecycleState())) {
            throw new ConflictException(ERR_ILLEGAL_TRANSITION,
                    "Action is " + decision.getLifecycleState() + ", expected " + expected.name());
        }
    }

    private static void requireOpen(DecisionItem decision) {
        String state = decision.getLifecycleState();
        boolean open = LifecycleState.HELD.name().equals(state)
                || LifecycleState.PENDING_CONFIRM.name().equals(state)
                || LifecycleState.PENDING_APPROVAL.name().equals(state);
        if (!open) {
            throw new ConflictException(ERR_ILLEGAL_TRANSITION,
                    "Action is " + state + " and cannot be cancelled");
        }
    }

    private static void requireApprover(AuthPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("Authentication required to approve");
        }
        // Four-eyes authorisation: the designated business APPROVER, or the bank's ADMIN acting as the
        // call-center (who verifies the customer out-of-band — a phone call — before authorising). A
        // CUSTOMER/OPS token can never approve. The PENDING_APPROVAL state is enforced separately by
        // requireState(), so an action can only be authorised while it is actually awaiting approval.
        if (principal.role() != Role.APPROVER && principal.role() != Role.ADMIN) {
            throw new ForbiddenException(ERR_NOT_AUTHORISED,
                    "Only a designated approver or an admin (call-center) may approve or reject");
        }
    }

    private static boolean isSelfApproval(AuthPrincipal principal, DecisionItem decision) {
        return principal.userId() != null
                && principal.userId().equals(decision.getInitiatorSub());
    }
}
