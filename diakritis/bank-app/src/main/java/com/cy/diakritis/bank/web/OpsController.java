package com.cy.diakritis.bank.web;

import com.cy.diakritis.bank.security.CurrentUser;
import com.cy.diakritis.bank.service.OpsService;
import com.cy.diakritis.bank.web.dto.ApprovalEntry;
import com.cy.diakritis.bank.web.dto.CountersView;
import com.cy.diakritis.bank.web.dto.FeedEntry;
import com.cy.diakritis.common.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operations console endpoints. Restricted to OPS and APPROVER roles. These are read-only
 * projections over the decision and case stores.
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
            description = "Read-only chronological feed of recent decisions. OPS or APPROVER role.")
    @GetMapping("/feed")
    public List<FeedEntry> feed(HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER);
        return opsService.feed();
    }

    @Operation(summary = "Operations counters",
            description = "Read-only aggregate counters over the decision store. OPS or APPROVER role.")
    @GetMapping("/counters")
    public CountersView counters(HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER);
        return opsService.counters();
    }

    @Operation(summary = "Pending approvals queue",
            description = "Read-only list of actions awaiting four-eyes approval. OPS or APPROVER role.")
    @GetMapping("/approvals")
    public List<ApprovalEntry> approvals(HttpServletRequest request) {
        currentUser.requireRole(request, Role.OPS, Role.APPROVER);
        return opsService.approvals();
    }
}
