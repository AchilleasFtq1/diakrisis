package com.cy.diakritis.iam.web;

import com.cy.diakritis.common.persistence.UserItem;
import com.cy.diakritis.common.security.Role;
import com.cy.diakritis.iam.service.BadRequestException;
import com.cy.diakritis.iam.service.IssuedTokens;
import com.cy.diakritis.iam.service.UserService;
import com.cy.diakritis.iam.web.dto.LoginRequest;
import com.cy.diakritis.iam.web.dto.LoginResponse;
import com.cy.diakritis.iam.web.dto.LogoutRequest;
import com.cy.diakritis.iam.web.dto.RefreshRequest;
import com.cy.diakritis.iam.web.dto.RefreshResponse;
import com.cy.diakritis.iam.web.dto.RegisterRequest;
import com.cy.diakritis.iam.web.dto.RegisterResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authentication endpoints. All paths under {@code /auth/**} are public (permitAll in the security
 * chain): they establish identity rather than requiring it.
 *
 * <ul>
 *   <li>{@code POST /auth/register} — self-service registration → 201 (409 username taken,
 *       400 weak password via bean validation).</li>
 *   <li>{@code POST /auth/login} — credential exchange → 200 with access + refresh tokens
 *       (401 on bad creds or a disabled account — the two are indistinguishable by design).</li>
 *   <li>{@code POST /auth/refresh} — rotate refresh → new access + refresh (401 invalid/expired/revoked).</li>
 *   <li>{@code POST /auth/logout} — revoke a refresh token (body field or
 *       {@code Authorization: Bearer <refreshToken>} header) → 204 (idempotent).</li>
 * </ul>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Register a new user",
            description = "Self-service registration. Always creates an UNBOUND CUSTOMER: privileged roles are "
                    + "assignable only via the ADMIN-guarded admin console, and account binding is established "
                    + "only by a trusted path (admin console / bank back-end) — a caller-supplied accountId is "
                    + "ignored here. 409 if the username is taken; 400 on a weak (too-short) password; "
                    + "422 if a non-CUSTOMER role is requested.")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        // SECURITY: /auth/register is public (permitAll). Two things must never be honored from an
        // unauthenticated caller here:
        //   1. a privileged role — that would be a vertical privilege escalation (rejected 422 below);
        //   2. an accountId — binding to a pre-existing account is the SOLE proof of account authority
        //      downstream (DecisionService/LifecycleService authorize a CUSTOMER purely by the accountId
        //      claim), so letting an unauthenticated caller pick an arbitrary accountId is a cross-account
        //      takeover (IDOR). We therefore register an UNBOUND CUSTOMER and ignore request.accountId();
        //      account binding is performed only via the admin console or the trusted bank back-end.
        Role requestedRole = UserService.parseRole(request.role()).orElse(Role.CUSTOMER);
        if (requestedRole != Role.CUSTOMER) {
            throw new BadRequestException(
                    "Self-service registration may only create a CUSTOMER account; "
                            + "privileged roles are assigned by an administrator.");
        }
        UserItem user = userService.register(
                request.username(), request.password(), Role.CUSTOMER, null);
        return new RegisterResponse(user.getUserId(), user.getUsername(), UserService.primaryRole(user).name());
    }

    @Operation(summary = "Authenticate and mint access + refresh tokens",
            description = "Validates credentials against the persisted users (BCrypt) and returns a short-lived "
                    + "access JWT plus an opaque, revocable refresh token. 401 on bad credentials OR a disabled "
                    + "account (the two are deliberately indistinguishable so the response cannot confirm an "
                    + "account's existence or credentials). Paste the access token into the Swagger Authorize "
                    + "dialog.")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        IssuedTokens tokens = userService.authenticate(request.username(), request.password());
        return new LoginResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.sub(),
                List.of(tokens.role().name()),
                tokens.accountId(),
                tokens.expiresAt());
    }

    @Operation(summary = "Refresh an access token",
            description = "Exchanges a valid refresh token for a new access token and a freshly rotated refresh "
                    + "token (the presented token is revoked). 401 if the refresh token is invalid, expired, or "
                    + "revoked.")
    @PostMapping("/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        IssuedTokens tokens = userService.refresh(request.refreshToken());
        return new RefreshResponse(tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt());
    }

    @Operation(summary = "Log out (revoke a refresh token)",
            description = "Revokes the presented refresh token so it can no longer be exchanged. The token may "
                    + "be supplied in the body field or, as a fallback, via the "
                    + "Authorization: Bearer <refreshToken> header. Idempotent: a missing or already-revoked "
                    + "token still returns 204.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request,
                                       @RequestHeader(value = "Authorization", required = false)
                                       String authorizationHeader) {
        String bodyToken = request == null ? null : request.refreshToken();
        // The body field is authoritative; fall back to the documented Authorization: Bearer header.
        String refreshToken = (bodyToken != null && !bodyToken.isBlank())
                ? bodyToken
                : bearerToken(authorizationHeader);
        userService.logout(refreshToken);
        return ResponseEntity.noContent().build();
    }

    /** Extract the credential from an {@code Authorization: Bearer <value>} header, or null if absent. */
    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String value = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return value.isBlank() ? null : value;
    }
}
