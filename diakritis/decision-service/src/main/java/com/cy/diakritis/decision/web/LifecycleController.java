package com.cy.diakritis.decision.web;

import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.decision.service.LifecycleResult;
import com.cy.diakritis.decision.service.LifecycleService;
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

    @PostMapping(path = "/actions/{id}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResult confirm(@PathVariable("id") String eventId) {
        return lifecycleService.confirm(eventId);
    }

    @PostMapping(path = "/actions/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResult cancel(@PathVariable("id") String eventId) {
        return lifecycleService.cancel(eventId);
    }

    @PostMapping(path = "/actions/{id}/release", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResult release(@PathVariable("id") String eventId) {
        return lifecycleService.release(eventId);
    }

    @PostMapping(path = "/actions/{id}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResult approve(@PathVariable("id") String eventId, HttpServletRequest request) {
        AuthPrincipal principal = CurrentPrincipal.from(request);
        return lifecycleService.approve(eventId, principal);
    }

    @PostMapping(path = "/actions/{id}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
    public LifecycleResult reject(@PathVariable("id") String eventId, HttpServletRequest request) {
        AuthPrincipal principal = CurrentPrincipal.from(request);
        return lifecycleService.reject(eventId, principal);
    }
}
