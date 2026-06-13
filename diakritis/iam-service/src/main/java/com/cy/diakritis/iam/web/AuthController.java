package com.cy.diakritis.iam.web;

import com.cy.diakritis.common.persistence.UserItem;
import com.cy.diakritis.common.security.Role;
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
 *       (401 bad creds, 403 disabled).</li>
 *   <li>{@code POST /auth/refresh} — rotate refresh → new access + refresh (401 invalid/expired/revoked).</li>
 *   <li>{@code POST /auth/logout} — revoke a refresh token → 204 (idempotent).</li>
 * </ul>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Register a new user",
            description = "Creates a user with a BCrypt-hashed password. role defaults to CUSTOMER; "
                    + "accountId binds a CUSTOMER to an account. 409 if the username is taken; 400 on a "
                    + "weak (too-short) password.")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        Role role = UserService.parseRole(request.role()).orElse(Role.CUSTOMER);
        UserItem user = userService.register(request.username(), request.password(), role, request.accountId());
        return new RegisterResponse(user.getUserId(), user.getUsername(), UserService.primaryRole(user).name());
    }

    @Operation(summary = "Authenticate and mint access + refresh tokens",
            description = "Validates credentials against the persisted users (BCrypt) and returns a short-lived "
                    + "access JWT plus an opaque, revocable refresh token. 401 on bad credentials; 403 if the "
                    + "user is disabled. Paste the access token into the Swagger Authorize dialog.")
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
            description = "Revokes the presented refresh token so it can no longer be exchanged. Idempotent: a "
                    + "missing or already-revoked token still returns 204.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request) {
        String refreshToken = request == null ? null : request.refreshToken();
        userService.logout(refreshToken);
        return ResponseEntity.noContent().build();
    }
}
