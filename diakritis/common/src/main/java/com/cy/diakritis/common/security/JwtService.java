package com.cy.diakritis.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Stateless HS256 token mint/verify shared by bank-app (mint) and both services (verify).
 *
 * <p>The signing key is derived from {@code diakrisis.jwt.secret}; HS256 requires at least
 * 256 bits (32 bytes) of key material, which is validated by {@link Keys#hmacShaKeyFor}.
 */
public class JwtService {

    static final String CLAIM_ROLE = "role";
    static final String CLAIM_ACCOUNT_ID = "accountId";

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;

    public JwtService(String secret) {
        if (secret == null) {
            throw new IllegalArgumentException("JWT secret must not be null");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256; got " + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Issues a signed token. Claims: {@code sub}, {@code role}, {@code accountId}.
     *
     * @param sub       the subject (user id); must not be null
     * @param role      the caller's role; must not be null
     * @param accountId the bound account id, may be null for non-account roles (OPS/APPROVER)
     * @param ttl       token lifetime; must be positive
     * @return the compact serialized JWT
     */
    public String issue(String sub, Role role, String accountId, Duration ttl) {
        if (sub == null || role == null || ttl == null) {
            throw new IllegalArgumentException("sub, role and ttl must not be null");
        }
        Instant now = Instant.now();
        Instant expiry = now.plus(ttl);
        return Jwts.builder()
                .subject(sub)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_ACCOUNT_ID, accountId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies signature and expiry and extracts the principal.
     *
     * @param token the compact JWT
     * @return the resolved principal
     * @throws JwtException if the token is malformed, expired, or has an invalid signature
     */
    public AuthPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("Empty token");
        }
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String sub = claims.getSubject();
        String roleName = claims.get(CLAIM_ROLE, String.class);
        String accountId = claims.get(CLAIM_ACCOUNT_ID, String.class);
        if (roleName == null) {
            throw new JwtException("Missing role claim");
        }
        Role role;
        try {
            role = Role.valueOf(roleName);
        } catch (IllegalArgumentException ex) {
            throw new JwtException("Unknown role claim: " + roleName, ex);
        }
        return new AuthPrincipal(sub, role, accountId);
    }
}
