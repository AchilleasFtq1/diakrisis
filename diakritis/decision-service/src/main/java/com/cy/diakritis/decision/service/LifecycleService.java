package com.cy.diakritis.decision.service;

import com.cy.diakritis.common.dto.LifecycleState;
import com.cy.diakritis.common.persistence.CaseItem;
import com.cy.diakritis.common.persistence.DecisionItem;
import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.common.security.Role;
import com.cy.diakritis.decision.repo.CaseRepository;
import com.cy.diakritis.decision.repo.DecisionRepository;
import com.cy.diakritis.decision.web.error.ConflictException;
import com.cy.diakritis.decision.web.error.ForbiddenException;
import com.cy.diakritis.decision.web.error.NotFoundException;
import com.cy.diakritis.decision.web.error.UnauthorizedException;
import org.springframework.stereotype.Service;

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
    public static final String ERR_LOCKED_PRE_EXPIRY = "LOCKED_PRE_EXPIRY";
    public static final String ERR_ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";

    private final DecisionRepository decisionRepository;
    private final CaseRepository caseRepository;

    public LifecycleService(DecisionRepository decisionRepository, CaseRepository caseRepository) {
        this.decisionRepository = decisionRepository;
        this.caseRepository = caseRepository;
    }

    /** Customer confirms a CONFIRM-banded action → executed. */
    public LifecycleResult confirm(String eventId) {
        DecisionItem decision = require(eventId);
        requireState(decision, LifecycleState.PENDING_CONFIRM);
        return transition(decision, LifecycleState.EXECUTED);
    }

    /** Customer abandons a held / pending action → cancelled. */
    public LifecycleResult cancel(String eventId) {
        DecisionItem decision = require(eventId);
        requireOpen(decision);
        return transition(decision, LifecycleState.CANCELLED);
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
        return transition(decision, LifecycleState.EXECUTED);
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
        return transitionWithApprover(decision, LifecycleState.REJECTED, principal, caseItem);
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
     * Build the transition result. For a batch case (mass-payment) on approval, the held items are
     * executed and the (empty) remainder reported held, per the {@code {items_executed, items_held}}
     * contract.
     */
    private LifecycleResult resultFor(DecisionItem decision, LifecycleState target, CaseItem caseItem) {
        if (caseItem != null && caseItem.getBatchHeldItemIds() != null
                && !caseItem.getBatchHeldItemIds().isEmpty()) {
            if (target == LifecycleState.EXECUTED) {
                return LifecycleResult.batch(decision.getEventId(), target,
                        List.copyOf(caseItem.getBatchHeldItemIds()), List.of());
            }
            if (target == LifecycleState.REJECTED || target == LifecycleState.CANCELLED) {
                return LifecycleResult.batch(decision.getEventId(), target,
                        List.of(), List.copyOf(caseItem.getBatchHeldItemIds()));
            }
        }
        return LifecycleResult.of(decision.getEventId(), target);
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
        if (principal.role() != Role.APPROVER) {
            throw new ForbiddenException(ERR_SELF_APPROVAL,
                    "Only a designated approver may approve or reject");
        }
    }

    private static boolean isSelfApproval(AuthPrincipal principal, DecisionItem decision) {
        return principal.userId() != null
                && principal.userId().equals(decision.getInitiatorSub());
    }
}
