package com.cy.diakritis.ops.web;

import com.cy.diakritis.common.security.Role;
import com.cy.diakritis.ops.security.CurrentUser;
import com.cy.diakritis.ops.service.OpsService;
import com.cy.diakritis.ops.web.dto.AccountView;
import com.cy.diakritis.ops.web.dto.ApprovalEntry;
import com.cy.diakritis.ops.web.dto.CountersView;
import com.cy.diakritis.ops.web.dto.CounterpartyView;
import com.cy.diakritis.ops.web.dto.FeedEntry;
import com.cy.diakritis.ops.web.dto.OutcomeView;
import com.cy.diakritis.ops.web.dto.Page;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Operations console endpoints. Restricted to OPS and APPROVER roles — coarsely by the security chain
 * ({@code /ops/** → hasAnyRole(OPS, APPROVER)}) and locally by {@link CurrentUser#requireRole} as a
 * defense-in-depth complement. These are read-only projections over the decision and case stores.
 */
@RestController
@RequestMapping("/ops")
public class OpsController {

    private final OpsService opsService;
    private final CurrentUser currentUser;

    public OpsController(OpsService opsService, CurrentUser currentUser) {
        this.opsService = opsService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Operations decision feed",
            description = "Server-paged chronological feed of recent decisions, filterable by outcome and "
                    + "free text. OPS or APPROVER role.")
    @GetMapping("/feed")
    public Page<FeedEntry> feed(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "15") int size,
            @RequestParam(name = "outcomes", required = false) List<String> outcomes,
            @RequestParam(name = "q", required = false) String query,
            HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER, Role.ADMIN);
        return opsService.feed(page, size, outcomes, query);
    }

    @Operation(summary = "Operations counters",
            description = "Read-only aggregate counters over the decision store. OPS or APPROVER role.")
    @GetMapping("/counters")
    public CountersView counters(HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER, Role.ADMIN);
        return opsService.counters();
    }

    @Operation(summary = "Pending approvals queue",
            description = "Server-paged list of actions awaiting four-eyes approval, filterable by initiator "
                    + "and free text. OPS or APPROVER role.")
    @GetMapping("/approvals")
    public Page<ApprovalEntry> approvals(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "8") int size,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "initiator", required = false) String initiator,
            HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER, Role.ADMIN);
        return opsService.approvals(page, size, query, initiator);
    }

    @Operation(summary = "Full decision detail",
            description = "The stored verbatim decision for one event (score, signals, typologies, "
                    + "explanation, combined, latency). OPS or APPROVER role.")
    @GetMapping("/decisions/{id}")
    public JsonNode decision(@PathVariable("id") String eventId, HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER, Role.ADMIN);
        JsonNode decision = opsService.decision(eventId);
        if (decision == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No decision for event " + eventId);
        }
        return decision;
    }

    @Operation(summary = "Account ops view",
            description = "Risk posture (funds-freed window), device/IP/geo observations, and the recent "
                    + "decision window for one account — the kill-chain memory. OPS or APPROVER role.")
    @GetMapping("/accounts/{id}")
    public AccountView account(@PathVariable("id") String accountId, HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER, Role.ADMIN);
        return opsService.accountView(accountId);
    }

    @Operation(summary = "Account decision history",
            description = "Server-paged full decision history for one account (newest first). "
                    + "OPS or APPROVER role.")
    @GetMapping("/accounts/{id}/history")
    public Page<FeedEntry> accountHistory(
            @PathVariable("id") String accountId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER, Role.ADMIN);
        return opsService.accountHistory(accountId, page, size);
    }

    @Operation(summary = "Flagged counterparties (mule intelligence)",
            description = "Server-paged beneficiaries the engine has flagged, most-flagged first, with "
                    + "fan-in (distinct paying accounts). Filterable by free text. OPS or APPROVER role.")
    @GetMapping("/counterparties")
    public Page<CounterpartyView> counterparties(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "15") int size,
            @RequestParam(name = "q", required = false) String query,
            HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER, Role.ADMIN);
        return opsService.counterparties(page, size, query);
    }

    @Operation(summary = "Recorded outcomes (wins board)",
            description = "Server-paged confirmed-saves and false-positives, newest first. Optionally "
                    + "filtered by type (CONFIRMED_SAVE / FALSE_POSITIVE). OPS or APPROVER role.")
    @GetMapping("/outcomes")
    public Page<OutcomeView> outcomes(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "15") int size,
            @RequestParam(name = "type", required = false) String type,
            HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER, Role.ADMIN);
        return opsService.outcomes(page, size, type);
    }
}
