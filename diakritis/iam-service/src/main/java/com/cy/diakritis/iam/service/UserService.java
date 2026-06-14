package com.cy.diakritis.iam.service;

import com.cy.diakritis.common.persistence.RefreshTokenItem;
import com.cy.diakritis.common.persistence.UserItem;
import com.cy.diakritis.common.security.JwtService;
import com.cy.diakritis.common.security.PasswordHasher;
import com.cy.diakritis.common.security.Role;
import com.cy.diakritis.iam.repo.RefreshTokenRepository;
import com.cy.diakritis.iam.repo.UserRepository;
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
 *   <li><b>refresh</b> — validate the refresh token (exists, not revoked, not expired, current
 *       credential generation), rotate it (revoke the old, mint a new) and issue a fresh access token.</li>
 *   <li><b>logout</b> — revoke a refresh token (idempotent).</li>
 *   <li>admin CRUD over users (list / get / create / update / assign-role / enable / disable / delete).</li>
 * </ul>
 *
 * <p>Refresh tokens are opaque high-entropy ids (NOT JWTs) so they are revocable and rotated on every
 * refresh. Each token id embeds the user's credential generation ({@link UserItem#getTokenGeneration()})
 * at mint time ({@code <generation>.<random>}); a credential reset or rename bumps the generation,
 * logically invalidating every outstanding token in one write. The primary role encoded in the access
 * JWT is the first entry of {@link UserItem#getRoles()}.
 */
@Service
public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    /** Opaque refresh tokens live far longer than the 15-minute access token; rotated on each use. */
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private static final int REFRESH_TOKEN_BYTES = 32;

    /** Separates the embedded credential generation from the random secret in a token id. Not in the
     *  Base64-URL alphabet, so it can never collide with the random component. */
    private static final char TOKEN_GENERATION_SEPARATOR = '.';

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
     * Register a new user. The username is normalized (trimmed + lower-cased) and must be unique; the
     * password is BCrypt-hashed (never stored in plaintext). {@code role} defaults to
     * {@link Role#CUSTOMER}; {@code accountId} binds a customer to an account and is ignored for
     * non-account roles. Uniqueness is enforced by an atomic conditional write, so concurrent
     * registrations of the same username cannot overwrite each other.
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
        // Canonicalize once so the stored key and the displayed username always agree, and so
        // "alice" / " Alice " / "ALICE" resolve to a single account.
        String canonicalUsername = UserRepository.normalize(username);
        if (canonicalUsername.isBlank()) {
            throw new BadRequestException("username must not be blank");
        }
        // Fast-path 409 for the common (non-concurrent) case; the conditional write below is the
        // authoritative guard against the check-then-put race.
        if (userRepository.existsByUsername(canonicalUsername)) {
            throw new ConflictException("Username already taken: " + canonicalUsername);
        }
        Role effectiveRole = role == null ? Role.CUSTOMER : role;
        UserItem user = new UserItem();
        user.setPk(UserRepository.partitionKeyFor(canonicalUsername));
        user.setUserId(UUID.randomUUID().toString());
        user.setUsername(canonicalUsername);
        user.setPasswordHash(passwordHasher.hash(rawPassword));
        user.setRoles(List.of(effectiveRole.name()));
        user.setAccountId(effectiveRole == Role.CUSTOMER ? accountId : null);
        user.setEnabled(true);
        user.setCreatedEpochMs(Instant.now().toEpochMilli());
        user.setTokenGeneration(0L);
        userRepository.saveIfAbsent(user);
        LOG.info("Registered user {} (role={}, accountId={})", canonicalUsername, effectiveRole, user.getAccountId());
        return user;
    }

    /**
     * Authenticate a username + password. Loads the user, verifies the BCrypt hash, and asserts the
     * account is enabled.
     *
     * <p>A disabled account returns the same generic 401 as a bad password: revealing "disabled but
     * the password was correct" via a distinct 403 would confirm both the account's existence and its
     * credentials to an attacker. The hash is still verified first so the disabled branch is reached
     * only after a real comparison (no timing oracle).
     *
     * @throws UnauthorizedException if the username is unknown, the password does not match, or the
     *                               account is disabled
     */
    public IssuedTokens authenticate(String username, String rawPassword) {
        UserItem user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password"));
        if (!passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid username or password");
        }
        if (!user.isEnabled()) {
            throw new UnauthorizedException("Invalid username or password");
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
     * is revoked via an atomic compare-and-set, so a leaked refresh token is single-use even under
     * concurrent presentation: exactly one of two racing callers wins the revoke and mints a new pair;
     * the loser gets a 401.
     *
     * @throws UnauthorizedException if the token is unknown, revoked, expired, superseded by a
     *                               credential change, or already consumed by a concurrent caller
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
            // The user is disabled; best-effort revoke (ignore the race outcome) and reject.
            refreshTokenRepository.revokeIfActive(stored);
            throw new UnauthorizedException("User account is disabled");
        }
        // A password reset / rename bumps the user's credential generation, retiring every token minted
        // under an older generation. Reject any token whose embedded generation is stale.
        if (tokenGenerationOf(refreshTokenId) != user.getTokenGeneration()) {
            throw new UnauthorizedException("Refresh token has been superseded");
        }

        // Rotate: atomically consume the presented token, then mint a new pair. Only the caller that
        // wins the compare-and-set may issue tokens, so a concurrent duplicate cannot fork the chain.
        if (!refreshTokenRepository.revokeIfActive(stored)) {
            throw new UnauthorizedException("Refresh token has already been used");
        }
        return issueTokens(user);
    }

    /** Revoke a refresh token. Idempotent: an unknown / already-revoked token is a no-op success. */
    public void logout(String refreshTokenId) {
        if (refreshTokenId == null || refreshTokenId.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenId(refreshTokenId)
                .ifPresent(refreshTokenRepository::revokeIfActive);
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
     * unchanged, so a partial update is expressible. {@code callerUsername} is the authenticated
     * operator performing the change (used to block self-lockout); it may be {@code null} for internal
     * callers that do not originate from an admin request.
     *
     * <p>Guards: an operation may not remove the platform's last enabled ADMIN (disable, demote, or —
     * via {@link #deleteUser}) delete), and an admin may not disable or demote <em>themselves</em>;
     * both would render the admin console permanently inaccessible.
     */
    public UserItem updateUser(String callerUsername, String username, Role role, Boolean enabled, String accountId) {
        UserItem user = getUser(username);

        boolean demotingFromAdmin = role != null
                && primaryRole(user) == Role.ADMIN
                && role != Role.ADMIN;
        boolean disabling = Boolean.FALSE.equals(enabled) && user.isEnabled();

        if (demotingFromAdmin || disabling) {
            guardSelfLockout(callerUsername, user, demotingFromAdmin
                    ? "demote your own admin account"
                    : "disable your own admin account");
            guardLastAdmin(user, "demote or disable the last enabled admin account");
        }

        if (role != null) {
            user.setRoles(List.of(role.name()));
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        // Account binding is valid ONLY for a CUSTOMER. Resolve the effective role (the new role if one
        // was supplied, else the user's current role) and enforce the invariant regardless of the order
        // fields arrive in: a non-account role can never retain or gain an accountId, and a CUSTOMER's
        // binding is updated only when an accountId was supplied (a blank clears it).
        Role effectiveRole = role != null ? role : primaryRole(user);
        if (effectiveRole != Role.CUSTOMER) {
            user.setAccountId(null);
        } else if (accountId != null) {
            user.setAccountId(accountId.isBlank() ? null : accountId);
        }
        userRepository.save(user);
        return user;
    }

    /** Assign (replace) the user's primary role. */
    public UserItem assignRole(String callerUsername, String username, Role role) {
        if (role == null) {
            throw new BadRequestException("role must not be null");
        }
        return updateUser(callerUsername, username, role, null, null);
    }

    public UserItem setEnabled(String callerUsername, String username, boolean enabled) {
        return updateUser(callerUsername, username, null, enabled, null);
    }

    public void deleteUser(String callerUsername, String username) {
        // Surface a 404 if the user does not exist, so DELETE is explicit about an unknown target.
        UserItem user = getUser(username);
        if (primaryRole(user) == Role.ADMIN && user.isEnabled()) {
            guardSelfLockout(callerUsername, user, "delete your own admin account");
            guardLastAdmin(user, "delete the last enabled admin account");
        }
        userRepository.deleteByUsername(username);
        LOG.info("Deleted user {}", username);
    }

    /**
     * Admin password reset — re-hash and store a new password. The plaintext is never logged. Bumping
     * the credential generation invalidates every outstanding refresh token so a reset evicts any
     * session an attacker may already hold (the primary purpose of a reset).
     */
    public UserItem resetPassword(String username, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BadRequestException("password must not be blank");
        }
        UserItem user = getUser(username);
        user.setPasswordHash(passwordHasher.hash(rawPassword));
        user.setTokenGeneration(user.getTokenGeneration() + 1);
        userRepository.save(user);
        LOG.info("Admin reset password for user {} (refresh tokens invalidated)", username);
        return user;
    }

    /**
     * Rename a user. Username is the partition key, so this re-keys the record: the same identity
     * (userId, password hash, roles, account, enabled, created-at) is written under the new (normalized)
     * username and the old row is deleted — atomically, in a single transaction, so a partial failure
     * can never leave two persisted rows for one identity. The new username must be free. The credential
     * generation is bumped so the user's existing refresh tokens (minted under the old generation) can
     * no longer be exchanged (forced re-login); access tokens already issued remain valid only until
     * they expire.
     */
    public UserItem rename(String username, String newUsername) {
        if (newUsername == null || newUsername.isBlank()) {
            throw new BadRequestException("new username must not be blank");
        }
        String target = UserRepository.normalize(newUsername);
        if (target.isBlank()) {
            throw new BadRequestException("new username must not be blank");
        }
        UserItem existing = getUser(username); // 404 if the source user is unknown
        String canonicalSource = UserRepository.normalize(username);
        if (target.equals(canonicalSource)) {
            return existing; // no-op rename
        }
        // Fast-path 409; the conditional Put inside the transaction is the authoritative guard.
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
        // Retire refresh tokens bound to the old identity (they also carry the old username).
        renamed.setTokenGeneration(existing.getTokenGeneration() + 1);
        userRepository.renameAtomically(renamed, canonicalSource);
        LOG.info("Renamed user {} -> {}", canonicalSource, target);
        return renamed;
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * The primary role encoded in the access token: the first entry of the user's role list.
     * Defaults to {@link Role#CUSTOMER} if the list is empty / unreadable, so a corrupt or unknown
     * stored literal degrades safely instead of throwing (which would break login/refresh).
     */
    public static Role primaryRole(UserItem user) {
        List<String> roles = user.getRoles();
        if (roles == null || roles.isEmpty()) {
            return Role.CUSTOMER;
        }
        try {
            return Role.valueOf(roles.get(0));
        } catch (IllegalArgumentException ex) {
            LOG.warn("User {} has unrecognized stored role '{}'; defaulting to CUSTOMER",
                    user.getUsername(), roles.get(0));
            return Role.CUSTOMER;
        }
    }

    /**
     * Reject an operation that would strip the calling admin of their own administrative access. The
     * caller's identity is the JWT subject (the username) carried by the access token.
     */
    private static void guardSelfLockout(String callerUsername, UserItem target, String action) {
        if (callerUsername != null
                && UserRepository.normalize(callerUsername).equals(UserRepository.normalize(target.getUsername()))) {
            throw new ConflictException("You cannot " + action);
        }
    }

    /**
     * Reject an operation that would drop the count of enabled ADMIN accounts to zero. The {@code target}
     * is the user about to be disabled/demoted/deleted; if it is the only remaining enabled admin, the
     * operation is refused so the platform cannot be locked out of its own admin console.
     */
    private void guardLastAdmin(UserItem target, String action) {
        long otherEnabledAdmins = userRepository.findAll().stream()
                .filter(other -> !UserRepository.normalize(other.getUsername())
                        .equals(UserRepository.normalize(target.getUsername())))
                .filter(UserItem::isEnabled)
                .filter(other -> primaryRole(other) == Role.ADMIN)
                .count();
        if (otherEnabledAdmins == 0) {
            throw new ConflictException("Refusing to " + action + ": at least one enabled admin must remain");
        }
    }

    private String mintRefreshToken(UserItem user, Role role) {
        byte[] raw = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // Bind the token to the user's current credential generation: <generation>.<secret>. A reset or
        // rename bumps the generation, retiring every token that embeds an older one.
        String tokenId = user.getTokenGeneration() + String.valueOf(TOKEN_GENERATION_SEPARATOR) + secret;

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

    /**
     * Extract the credential generation embedded in a token id ({@code <generation>.<secret>}). A
     * malformed / legacy token without a parseable generation prefix is treated as generation
     * {@code -1}, which can never match a real (non-negative) user generation, so it is rejected.
     */
    private static long tokenGenerationOf(String tokenId) {
        int separator = tokenId.indexOf(TOKEN_GENERATION_SEPARATOR);
        if (separator <= 0) {
            return -1L;
        }
        try {
            return Long.parseLong(tokenId.substring(0, separator));
        } catch (NumberFormatException ex) {
            return -1L;
        }
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
