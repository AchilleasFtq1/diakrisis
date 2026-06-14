package com.cy.diakritis.decision.web;

import com.cy.diakritis.common.dto.ActionEvent;
import com.cy.diakritis.common.dto.Decision;
import com.cy.diakritis.common.persistence.DecisionItem;
import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.common.security.Role;
import com.cy.diakritis.decision.repo.DecisionRepository;
import com.cy.diakritis.decision.service.DecisionService;
import com.cy.diakritis.decision.web.error.ForbiddenException;
import com.cy.diakritis.decision.web.error.NotFoundException;
import com.cy.diakritis.decision.web.error.UnprocessableException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The decision API.
 *
 * <ul>
 *   <li>{@code POST /decision} — score an {@link ActionEvent} into a {@link Decision} (idempotent).</li>
 *   <li>{@code GET /decisions/{id}/why} — the audit + customer explanation for a stored decision.</li>
 * </ul>
 */
@RestController
public class DecisionController {

    private final DecisionService decisionService;
    private final DecisionRepository decisionRepository;
    private final JsonMapper jsonMapper;

    public DecisionController(DecisionService decisionService,
                              DecisionRepository decisionRepository,
                              JsonMapper jsonMapper) {
        this.decisionService = decisionService;
        this.decisionRepository = decisionRepository;
        this.jsonMapper = jsonMapper;
    }

    @Operation(summary = "Score an ActionEvent into a Decision (idempotent on event_id)",
            description = "Runs the rule engine + AI co-judge and returns the combined verdict, lifecycle, and "
                    + "dual-audience explanation. Idempotent: replaying the same event_id returns the stored "
                    + "response without re-scoring or mutating posture.")
    @PostMapping(path = "/decision",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Decision decide(@Valid @RequestBody ActionEvent event, HttpServletRequest request) {
        if (event.payload() == null) {
            // The eventType/payload pairing failed to resolve a concrete payload; well-formed JSON
            // but semantically invalid → 422 rather than a parse-time error.
            throw new UnprocessableException("payload could not be resolved for the given event_type");
        }
        AuthPrincipal principal = CurrentPrincipal.from(request);
        return decisionService.decide(event, principal);
    }

    @Operation(summary = "Explain a stored decision",
            description = "Returns the audit + customer explanation, reason code, and typologies for a previously "
                    + "scored event_id.")
    @GetMapping(path = "/decisions/{id}/why", produces = MediaType.APPLICATION_JSON_VALUE)
    public WhyResponse why(@PathVariable("id") String eventId, HttpServletRequest request) {
        DecisionItem item = decisionRepository.findByEventId(eventId)
                .orElseThrow(() -> new NotFoundException("No decision for event " + eventId));

        // Authorisation: a CUSTOMER token may only read the explanation for its OWN account's decision
        // (mirrors the ownership guard on POST /decision and the lifecycle actions). Without this guard
        // any authenticated user could enumerate event ids and read another account's audit narrative,
        // reason code and fraud typologies — a cross-account information disclosure (IDOR). Elevated
        // roles (OPS/APPROVER/ADMIN) and service callers may read any decision's full audit trail.
        AuthPrincipal principal = CurrentPrincipal.from(request);
        boolean isCustomer = principal != null && principal.role() == Role.CUSTOMER;
        if (isCustomer && (principal.accountId() == null
                || !principal.accountId().equals(item.getAccountId()))) {
            throw new ForbiddenException("ACCOUNT_OWNERSHIP_REQUIRED",
                    "This token may only read explanations for its own account");
        }

        JsonNode response = readResponse(item, eventId);
        JsonNode explanation = response.get("explanation");
        JsonNode reasonCode = response.get("reason_code");
        JsonNode engineVerdict = response.get("engine_verdict");
        JsonNode typologies = engineVerdict == null ? null : engineVerdict.get("typologies");

        if (isCustomer) {
            // The customer must NEVER see operator-only audit text, the engine reason code, or the named
            // fraud typologies — only the plain-English customer explanation (SDD: the raw engine
            // verdict/score/codes/typologies are never surfaced to the customer).
            if (explanation != null && explanation.isObject()) {
                ((ObjectNode) explanation).remove("audit");
            }
            return new WhyResponse(eventId, null, explanation, null);
        }

        return new WhyResponse(
                eventId,
                reasonCode == null || reasonCode.isNull() ? null : reasonCode.asText(),
                explanation == null ? null : explanation,
                typologies);
    }

    private JsonNode readResponse(DecisionItem item, String eventId) {
        try {
            return jsonMapper.readTree(item.getResponseJson());
        } catch (JacksonException ex) {
            throw new NotFoundException("Stored decision for " + eventId + " is unreadable");
        }
    }

    /** The {@code /why} projection: the reason code, the dual-audience explanation, and typologies. */
    public record WhyResponse(String eventId, String reasonCode, JsonNode explanation, JsonNode typologies) {
    }
}
