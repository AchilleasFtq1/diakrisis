package com.cy.demobank.client;

import com.cy.demobank.client.dto.ActionEventRequest;
import com.cy.demobank.client.dto.DecisionResponse;
import com.cy.demobank.client.dto.LoginRequest;
import com.cy.demobank.client.dto.LoginResponse;
import com.cy.demobank.config.DiakrisisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The HTTP client demo-bank uses to drive a Diakrisis decision. It:
 *
 * <ol>
 *   <li>logs the owning customer in to IAM ({@code POST /auth/login}) and caches the access JWT
 *       (per username, with a small skew before expiry), and</li>
 *   <li>POSTs the {@link ActionEventRequest} to the decision service ({@code POST /decision})
 *       forwarding {@code Authorization: Bearer <jwt>}.</li>
 * </ol>
 *
 * <p>Both base-urls come from {@link DiakrisisProperties}, so a single {@code diakrisis.gateway.base-url}
 * repoints login and decision behind the gateway (:8080) with no code change.
 */
@Component
public class DecisionClient {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionClient.class);

    /** Refresh a cached token this long before its real expiry to avoid using a just-expired JWT. */
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final DiakrisisProperties properties;

    /** Per-username cached access tokens. The demo's concurrency is light; a map suffices. */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public DecisionClient(RestClient diakrisisRestClient, DiakrisisProperties properties) {
        this.restClient = diakrisisRestClient;
        this.properties = properties;
    }

    /**
     * Score an action: ensure a JWT for {@code username}, then POST the event to the decision API.
     *
     * @param username the owning customer (e.g. {@code customer-A}); its password is the seeded demo one.
     * @param event    the snake_case ActionEvent envelope.
     * @return the decision verdict + explanation.
     * @throws DiakrisisClientException if login or scoring fails (network, 4xx/5xx).
     */
    public DecisionResponse decide(String username, ActionEventRequest event) {
        String token = accessToken(username);
        String url = properties.decisionBaseUrl() + "/decision";
        try {
            DecisionResponse response = restClient.post()
                    .uri(url)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(event)
                    .retrieve()
                    .body(DecisionResponse.class);
            if (response == null) {
                throw new DiakrisisClientException("decision-service returned an empty body");
            }
            LOG.info("Decision for event {} (account {}): {} score={} typologies={}",
                    event.eventId(), event.accountId(),
                    response.effectiveDecision(),
                    response.engineVerdict() == null ? null : response.engineVerdict().score(),
                    response.typologies());
            return response;
        } catch (RestClientResponseException ex) {
            // A 401 likely means a stale cached token (e.g. secret rotated): evict and surface a clear error.
            if (ex.getStatusCode().value() == 401) {
                tokenCache.remove(username);
            }
            throw new DiakrisisClientException(
                    "decision call failed (" + ex.getStatusCode() + "): " + ex.getResponseBodyAsString(), ex);
        } catch (RuntimeException ex) {
            throw new DiakrisisClientException("decision call to " + url + " failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Drive a lifecycle transition on a decided action (e.g. {@code confirm} a PENDING_CONFIRM step-up,
     * or {@code cancel} a HELD payment) — {@code POST /actions/{eventId}/{action}} with the customer's
     * Bearer. Throws {@link DiakrisisClientException} on any non-2xx (e.g. 409 if the action is not in
     * the right state).
     */
    public void act(String username, String eventId, String action) {
        String token = accessToken(username);
        String url = properties.decisionBaseUrl() + "/actions/" + eventId + "/" + action;
        try {
            restClient.post()
                    .uri(url)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity();
            LOG.info("Lifecycle {} for event {} by {}", action, eventId, username);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                tokenCache.remove(username);
            }
            throw new DiakrisisClientException(
                    action + " failed (" + ex.getStatusCode() + "): " + ex.getResponseBodyAsString(), ex);
        } catch (RuntimeException ex) {
            throw new DiakrisisClientException("action call to " + url + " failed: " + ex.getMessage(), ex);
        }
    }

    /** Return a valid cached access token for {@code username}, logging in on a miss or near-expiry. */
    private String accessToken(String username) {
        CachedToken cached = tokenCache.get(username);
        if (cached != null && cached.isFresh()) {
            return cached.token();
        }
        LoginResponse login = login(username);
        Instant expiresAt = login.expiresAt() == null
                ? Instant.now().plus(Duration.ofMinutes(5))
                : login.expiresAt();
        tokenCache.put(username, new CachedToken(login.token(), expiresAt));
        return login.token();
    }

    private LoginResponse login(String username) {
        String url = properties.iamBaseUrl() + "/auth/login";
        LoginRequest request = new LoginRequest(username, properties.getAuth().getPassword());
        try {
            LoginResponse response = restClient.post()
                    .uri(url)
                    .body(request)
                    .retrieve()
                    .body(LoginResponse.class);
            if (response == null || response.token() == null) {
                throw new DiakrisisClientException("IAM login returned no token for " + username);
            }
            LOG.info("Logged in to Diakrisis IAM as {} (account {})", username, response.accountId());
            return response;
        } catch (RestClientResponseException ex) {
            throw new DiakrisisClientException(
                    "login for " + username + " failed (" + ex.getStatusCode() + "): "
                            + ex.getResponseBodyAsString(), ex);
        } catch (RuntimeException ex) {
            throw new DiakrisisClientException("login call to " + url + " failed: " + ex.getMessage(), ex);
        }
    }

    /** A cached access token with its expiry; {@link #isFresh()} applies the skew. */
    private record CachedToken(String token, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt.minus(EXPIRY_SKEW));
        }
    }
}
