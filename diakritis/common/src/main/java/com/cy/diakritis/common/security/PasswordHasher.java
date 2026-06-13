package com.cy.diakritis.common.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt password hashing wrapper. Backed by Spring Security's {@link BCryptPasswordEncoder} (pulled
 * via {@code spring-security-crypto} only — NOT the full security starter, so the lightweight
 * {@link JwtAuthFilter} stays in place).
 *
 * <p>BCrypt embeds a per-password random salt and the cost factor inside the produced hash, so
 * {@link #matches(String, String)} re-derives the salt from the stored hash; no salt column is
 * needed. Plaintext passwords are never persisted.
 */
public final class PasswordHasher {

    private final PasswordEncoder delegate;

    public PasswordHasher() {
        // Default strength (cost 10) — adequate for a demo while still being a real adaptive hash.
        this.delegate = new BCryptPasswordEncoder();
    }

    /**
     * @param rawPassword the plaintext password; must not be null
     * @return a self-describing BCrypt hash (algorithm + cost + salt + digest)
     */
    public String hash(String rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("rawPassword must not be null");
        }
        return delegate.encode(rawPassword);
    }

    /**
     * @param rawPassword the candidate plaintext, may be null
     * @param storedHash  the stored BCrypt hash, may be null
     * @return true iff both are non-null and the plaintext matches the hash in constant-ish time
     */
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) {
            return false;
        }
        return delegate.matches(rawPassword, storedHash);
    }
}
