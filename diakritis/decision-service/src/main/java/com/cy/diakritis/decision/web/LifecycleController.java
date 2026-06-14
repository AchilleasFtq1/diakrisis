package com.cy.diakritis.decision.web;

import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.decision.service.LifecycleResult;
import com.cy.diakritis.decision.service.LifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lifecycle transitions on a decided action: {@code confirm}, {@code cancel}, {@code release},
 * {@code approve}, {@code reject}. The four-eyes and pre-expiry guards live in
 * {@link LifecycleService}; this controller only resolves the principal and delegates.
 */
@RestController
public class LifecycleController {

    private final LifecycleService lifecycleService;

    public LifecycleController(LifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Operation(summary = "Confirm a pending-confirm action",
            description = "Customer step-up: confirms a CONFIRM-banded action so it executes.")
    @PostMapping(path = "/actions/{id}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResult confirm(@PathVariable("id") String eventId, HttpServletRequest request) {
        AuthPrincipal principal = CurrentPrincipal.from(request);
        return lifecycleService.confirm(eventId, principal);
    }

    @Operation(summary = "Cancel a pending action",
            description = "Abandons a pending-confirm or held action without executing it.")
    @PostMapping(path = "/actions/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResult cancel(@PathVariable("id") String eventId, HttpServletRequest request) {
        AuthPrincipal principal = CurrentPrincipal.from(request);
        return lifecycleService.cancel(eventId, principal);
    }

    @Operation(summary = "Release a held action after its hold expires",
            description = "Releases a HELD action once the hold window has elapsed. Releasing before expiry "
                    + "returns 409 LOCKED_PRE_EXPIRY.")
    @PostMapping(path = "/actions/{id}/release", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResult release(@PathVariable("id") String eventId, HttpServletRequest request) {
        AuthPrincipal principal = CurrentPrincipal.from(request);
        return lifecycleService.release(eventId, principal);
    }

    @Operation(summary = "Approve an action requiring approval (four-eyes)",
            description = "APPROVER role only; the approver's sub must differ from the initiator's, else 403 "
                    + "SELF_APPROVAL_FORBIDDEN. Batch approvals return items_executed / items_held.")
    @PostMapping(path = "/actions/{id}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResult approve(@PathVariable("id") String eventId, HttpServletRequest request) {
        AuthPrincipal principal = CurrentPrincipal.from(request);
        return lifecycleService.approve(eventId, principal);
    }

    @Operation(summary = "Reject an action requiring approval (four-eyes)",
            description = "APPROVER role only; rejects an action that was awaiting approval.")
    @PostMapping(path = "/actions/{id}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResult reject(@PathVariable("id") String eventId, HttpServletRequest request) {
        AuthPrincipal principal = CurrentPrincipal.from(request);
        return lifecycleService.reject(eventId, principal);
    }
}
