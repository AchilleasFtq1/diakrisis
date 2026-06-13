package com.cy.diakritis.bank.web;

import com.cy.diakritis.bank.security.DemoUser;
import com.cy.diakritis.bank.security.DemoUserStore;
import com.cy.diakritis.bank.security.UnauthorizedException;
import com.cy.diakritis.bank.web.dto.LoginRequest;
import com.cy.diakritis.bank.web.dto.LoginResponse;
import com.cy.diakritis.common.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Authentication endpoint. Validates demo credentials and mints a JWT via the shared
 * {@link JwtService}. This path is public (bypassed by the JWT filter).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Duration TOKEN_TTL = Duration.ofHours(8);

    private final DemoUserStore demoUserStore;
    private final JwtService jwtService;

    public AuthController(DemoUserStore demoUserStore, JwtService jwtService) {
        this.demoUserStore = demoUserStore;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        DemoUser user = demoUserStore.authenticate(request.username(), request.password())
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password"));
        Instant expiresAt = Instant.now().plus(TOKEN_TTL);
        String token = jwtService.issue(user.username(), user.role(), user.accountId(), TOKEN_TTL);
        return new LoginResponse(token, user.username(), List.of(user.role().name()), expiresAt);
    }
}
