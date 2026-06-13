package com.cy.diakritis.iam.service;

import com.cy.diakritis.common.persistence.RefreshTokenItem;
import com.cy.diakritis.common.persistence.UserItem;
import com.cy.diakritis.common.security.JwtService;
import com.cy.diakritis.common.security.PasswordHasher;
import com.cy.diakritis.common.security.Role;
import com.cy.diakritis.iam.repo.RefreshTokenRepository;
import com.cy.diakritis.iam.repo.UserRepository;
import com.cy.diakritis.iam.security.ForbiddenException;
import com.cy.diakritis.iam.security.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The user / identity service backing {@code /auth/*} and {@code /admin/users/*}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>register</b> — unique username, BCrypt-hash the password, persist a {@link UserItem}.</li>
 *   <li><b>authenticate</b> — load by username, BCrypt-match, assert enabled.</li>
 *   <li><b>issueTokens</b> — mint a short-lived access JWT + persist an opaque refresh token.</li>
 *   <li><b>refresh</b> — validate the refresh token (exists, not revoked, not expired), rotate it
 *       (revoke the old, mint a new) and issue a fresh access token.</li>
 *   <li><b>logout</b> — revoke a refresh token (idempotent).</li>
 *   <li>admin CRUD over users (list / get / create / update / assign-role / enable / disable / delete).</li>
 * </ul>
 *
 * <p>Refresh tokens are opaque high-entropy ids (NOT JWTs) so they are revocable and rotated on every
 * refresh. The primary role encoded in the access JWT is the first entry of {@link UserItem#getRoles()}.
 */
@Service
public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    /** Opaque refresh tokens live far longer than the 15-minute access token; rotated on each use. */
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordHasher passwordHasher;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordHasher = passwordHasher;
    }

    // --- registration & authentication --------------------------------------------------------

    /**
     * Register a new user. The username must be unique; the password is BCrypt-hashed (never stored
     * in plaintext). {@code role} defaults to {@link Role#CUSTOMER}; {@code accountId} binds a
     * customer to an account and is ignored for non-account roles.
     *
     * @throws ConflictException if the username already exists
     */
    public UserItem register(String username, String rawPassword, Role role, String accountId) {
        if (username == null || username.isBlank()) {
            throw new BadRequestException("username must not be blank");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BadRequestException("password must not be blank");
        }
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("Username already taken: " + username);
        }
        Role effectiveRole = role == null ? Role.CUSTOMER : role;
        UserItem user = new UserItem();
        user.setPk(UserRepository.partitionKeyFor(username));
        user.setUserId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash(rawPassword));
        user.setRoles(List.of(effectiveRole.name()));
        user.setAccountId(effectiveRole == Role.CUSTOMER ? accountId : null);
        user.setEnabled(true);
        user.setCreatedEpochMs(Instant.now().toEpochMilli());
        userRepository.save(user);
        LOG.info("Registered user {} (role={}, accountId={})", username, effectiveRole, user.getAccountId());
        return user;
    }

    /**
     * Authenticate a username + password. Loads the user, verifies the BCrypt hash, and asserts the
     * account is enabled.
     *
     * @throws UnauthorizedException if the username is unknown or the password does not match
     * @throws ForbiddenException    if the user exists and matches but is disabled
     */
    public IssuedTokens authenticate(String username, String rawPassword) {
        UserItem user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password"));
        if (!passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid username or password");
        }
        if (!user.isEnabled()) {
            throw new ForbiddenException("User account is disabled");
        }
        return issueTokens(user);
    }

    /** Mint a short-lived access JWT and persist a freshly minted opaque refresh token for the user. */
    public IssuedTokens issueTokens(UserItem user) {
        Role role = primaryRole(user);
        Instant expiresAt = Instant.now().plus(JwtService.ACCESS_TOKEN_TTL);
        String accessToken = jwtService.issueAccessToken(user.getUsername(), role, user.getAccountId());
        String refreshToken = mintRefreshToken(user, role);
        return new IssuedTokens(accessToken, refreshToken, user.getUsername(), role,
                user.getAccountId(), expiresAt);
    }

    /**
     * Exchange a refresh token for a new access token and a rotated refresh token. The presented token
     * is revoked; a fresh one is issued (rotation), so a leaked refresh token is single-use.
     *
     * @throws UnauthorizedException if the token is unknown, revoked, or expired
     */
    public IssuedTokens refresh(String refreshTokenId) {
        if (refreshTokenId == null || refreshTokenId.isBlank()) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        RefreshTokenItem stored = refreshTokenRepository.findByTokenId(refreshTokenId)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        if (stored.isRevoked()) {
            throw new UnauthorizedException("Refresh token has been revoked");
        }
        if (stored.getExpiresEpochMs() <= Instant.now().toEpochMilli()) {
            throw new UnauthorizedException("Refresh token has expired");
        }

        // Re-load the user so a disabled/role-changed account cannot keep refreshing into access.
        UserItem user = userRepository.findByUsername(stored.getUsername())
                .orElseThrow(() -> new UnauthorizedException("User no longer exists"));
        if (!user.isEnabled()) {
            revoke(stored);
            throw new UnauthorizedException("User account is disabled");
        }

        // Rotate: revoke the presented token, mint a new pair.
        revoke(stored);
        return issueTokens(user);
    }

    /** Revoke a refresh token. Idempotent: an unknown / already-revoked token is a no-op success. */
    public void logout(String refreshTokenId) {
        if (refreshTokenId == null || refreshTokenId.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenId(refreshTokenId).ifPresent(this::revoke);
    }

    // --- admin user-management ----------------------------------------------------------------

    public List<UserItem> listUsers() {
        return userRepository.findAll();
    }

    public UserItem getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("No such user: " + username));
    }

    /** Admin create — same persistence path as self-registration but driven by an operator. */
    public UserItem createUser(String username, String rawPassword, Role role, String accountId) {
        return register(username, rawPassword, role, accountId);
    }

    /**
     * Update mutable fields of an existing user. Null arguments leave the corresponding field
     * unchanged, so a partial update is expressible.
     */
    public UserItem updateUser(String username, Role role, Boolean enabled, String accountId) {
        UserItem user = getUser(username);
        if (role != null) {
            user.setRoles(List.of(role.name()));
            // A non-account role cannot retain an account binding.
            if (role != Role.CUSTOMER) {
                user.setAccountId(null);
            }
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        if (accountId != null) {
            user.setAccountId(accountId.isBlank() ? null : accountId);
        }
        userRepository.save(user);
        return user;
    }

    /** Assign (replace) the user's primary role. */
    public UserItem assignRole(String username, Role role) {
        if (role == null) {
            throw new BadRequestException("role must not be null");
        }
        return updateUser(username, role, null, null);
    }

    public UserItem setEnabled(String username, boolean enabled) {
        return updateUser(username, null, enabled, null);
    }

    public void deleteUser(String username) {
        // Surface a 404 if the user does not exist, so DELETE is explicit about an unknown target.
        getUser(username);
        userRepository.deleteByUsername(username);
        LOG.info("Deleted user {}", username);
    }

    /** Admin password reset — re-hash and store a new password. The plaintext is never logged. */
    public UserItem resetPassword(String username, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BadRequestException("password must not be blank");
        }
        UserItem user = getUser(username);
        user.setPasswordHash(passwordHasher.hash(rawPassword));
        userRepository.save(user);
        LOG.info("Admin reset password for user {}", username);
        return user;
    }

    /**
     * Rename a user. Username is the partition key, so this re-keys the record: the same identity
     * (userId, password hash, roles, account, enabled, created-at) is written under the new username and
     * the old row is deleted. The new username must be free. The user's existing refresh tokens still
     * reference the old username, so they can no longer be exchanged (forced re-login) — access tokens
     * already issued remain valid only until they expire.
     */
    public UserItem rename(String username, String newUsername) {
        if (newUsername == null || newUsername.isBlank()) {
            throw new BadRequestException("new username must not be blank");
        }
        String target = newUsername.trim();
        UserItem existing = getUser(username); // 404 if the source user is unknown
        if (target.equals(username)) {
            return existing; // no-op rename
        }
        if (userRepository.existsByUsername(target)) {
            throw new ConflictException("Username already taken: " + target);
        }
        UserItem renamed = new UserItem();
        renamed.setPk(UserRepository.partitionKeyFor(target));
        renamed.setUserId(existing.getUserId());
        renamed.setUsername(target);
        renamed.setPasswordHash(existing.getPasswordHash());
        renamed.setRoles(existing.getRoles());
        renamed.setAccountId(existing.getAccountId());
        renamed.setEnabled(existing.isEnabled());
        renamed.setCreatedEpochMs(existing.getCreatedEpochMs());
        userRepository.save(renamed);
        userRepository.deleteByUsername(username);
        LOG.info("Renamed user {} -> {}", username, target);
        return renamed;
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * The primary role encoded in the access token: the first entry of the user's role list.
     * Defaults to {@link Role#CUSTOMER} if the list is empty / unreadable.
     */
    public static Role primaryRole(UserItem user) {
        List<String> roles = user.getRoles();
        if (roles == null || roles.isEmpty()) {
            return Role.CUSTOMER;
        }
        return Role.valueOf(roles.get(0));
    }

    private String mintRefreshToken(UserItem user, Role role) {
        byte[] raw = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String tokenId = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        RefreshTokenItem item = new RefreshTokenItem();
        item.setPk(RefreshTokenRepository.partitionKeyFor(tokenId));
        item.setTokenId(tokenId);
        item.setUserId(user.getUserId());
        item.setUsername(user.getUsername());
        item.setRole(role.name());
        item.setAccountId(user.getAccountId());
        item.setExpiresEpochMs(Instant.now().plus(REFRESH_TOKEN_TTL).toEpochMilli());
        item.setRevoked(false);
        refreshTokenRepository.save(item);
        return tokenId;
    }

    private void revoke(RefreshTokenItem token) {
        token.setRevoked(true);
        refreshTokenRepository.save(token);
    }

    /** Parse a role literal from a request, returning {@link Optional#empty()} when blank/absent. */
    public static Optional<Role> parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Role.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown role: " + raw);
        }
    }
}
