package com.cy.demobank.web;

import com.cy.demobank.client.DiakrisisClientException;
import com.cy.demobank.service.AccessDeniedException;
import com.cy.demobank.service.ActionResult;
import com.cy.demobank.service.BankService;
import com.cy.demobank.web.dto.BatchApiRequest;
import com.cy.demobank.web.dto.BeneficiaryApiRequest;
import com.cy.demobank.web.dto.P2pApiRequest;
import com.cy.demobank.web.dto.TransferApiRequest;
import com.cy.demobank.web.dto.TransferToNewApiRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A tiny JSON REST surface for scripted demos. Each endpoint drives the same {@link BankService}
 * money action the Thymeleaf forms do and returns the live verdict (the Diakrisis Decision, plus
 * whether demo-bank applied the balance change). The decision body is rendered by demo-bank's own
 * MVC JSON mapper; the inner verdict/typologies/explanation came verbatim from the decision-service.
 */
@RestController
@RequestMapping("/api")
@Validated
public class BankRestController {

    private final BankService bankService;

    public BankRestController(BankService bankService) {
        this.bankService = bankService;
    }

    @PostMapping("/transfer")
    public ActionResult transfer(@Valid @RequestBody TransferApiRequest request) {
        return bankService.transfer(request.customer(), request.account(), request.payee(), request.amount(),
                request.railOrDefault());
    }

    @PostMapping("/transfer-new")
    public ActionResult transferToNew(@Valid @RequestBody TransferToNewApiRequest request) {
        return bankService.transferToNew(request.customer(), request.account(), request.iban(), request.resolvedName(),
                request.amount(), request.railOrDefault());
    }

    @PostMapping("/p2p")
    public ActionResult p2p(@Valid @RequestBody P2pApiRequest request) {
        return bankService.p2p(request.customer(), request.account(), request.alias(), request.resolvedName(),
                request.amount());
    }

    @PostMapping("/beneficiaries")
    public ActionResult addBeneficiary(@Valid @RequestBody BeneficiaryApiRequest request) {
        String resolved = request.resolvedName() == null || request.resolvedName().isBlank()
                ? request.displayName() : request.resolvedName();
        return bankService.addBeneficiary(request.customer(), request.account(), request.iban(),
                request.displayName(), resolved);
    }

    /**
     * Break a term deposit. {@code customer} (the account owner) is required so this unauthenticated
     * surface can only break a deposit on an account whose owner the caller correctly names — the
     * service asserts ownership against the deposit's account.
     */
    @PostMapping("/deposits/{id}/break")
    public ActionResult breakDeposit(@PathVariable("id") String depositId,
                                     @RequestParam("customer") @NotBlank String customer) {
        return bankService.breakDeposit(customer, depositId);
    }

    @PostMapping("/batches")
    public ActionResult batch(@Valid @RequestBody BatchApiRequest request) {
        var lines = request.lines().stream()
                .map(line -> new BankService.BatchLine(line.itemId(), line.iban(),
                        line.resolvedName() == null || line.resolvedName().isBlank()
                                ? line.iban() : line.resolvedName(),
                        line.amount()))
                .toList();
        return bankService.massPayment(request.customer(), request.account(), lines, request.railOrDefault());
    }

    // ------------------------------------------------------------------------------------------------
    // Error mapping → JSON.
    // ------------------------------------------------------------------------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", ex.getMessage()));
    }

    /** Horizontal-authorization failure (acting on an account the named customer does not own) → 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> forbidden(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "FORBIDDEN", "message", ex.getMessage()));
    }

    @ExceptionHandler(DiakrisisClientException.class)
    public ResponseEntity<Map<String, String>> diakrisisError(DiakrisisClientException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "DIAKRISIS_UNAVAILABLE", "message", ex.getMessage()));
    }
}
