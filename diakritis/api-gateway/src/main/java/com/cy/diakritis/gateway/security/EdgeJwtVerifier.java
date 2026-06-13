package com.cy.diakritis.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Self-contained edge HS256 JWT validation.
 *
 * <p>Intentionally a tiny re-implementation rather than a reuse of the backend {@code common}
 * library: the gateway is pinned to Spring Boot 4.0.x and must not pull the Boot-4.1 {@code common}
 * jar onto its classpath. It reads the SAME {@code DIAKRISIS_JWT_SECRET} as the backends and validates
 * the SAME token shape minted by iam-service (subject {@code sub} + string claim {@code role}).
 *
 * <p>This is an EDGE gate only (authenticated yes/no + role for coarse {@code /admin} authorization).
 * The backend services still fully validate the token themselves (defense in depth).
 */
@Component
public class EdgeJwtVerifier {

    /** Role claim name — must match the backend {@code JwtService} CLAIM_ROLE. */
    private static final String CLAIM_ROLE = "role";

    /** HS256 requires at least 256 bits (32 bytes) of key material. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;

    public EdgeJwtVerifier(@Value("${diakrisis.jwt.secret}") String secret) {
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
     * Verifies the signature and expiry of a compact JWT and extracts the principal.
     *
     * @param token the compact serialized JWT (without the {@code Bearer } prefix); must be non-blank
     * @return the resolved {@link EdgePrincipal} (subject + role)
     * @throws JwtException if the token is null/blank, malformed, expired, or has an invalid signature
     */
    public EdgePrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("Empty token");
        }
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String subject = claims.getSubject();
        String role = claims.get(CLAIM_ROLE, String.class);
        return new EdgePrincipal(subject, role);
    }
}
