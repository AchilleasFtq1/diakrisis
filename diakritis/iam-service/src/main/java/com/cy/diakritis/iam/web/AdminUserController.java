package com.cy.diakritis.iam.web;

import com.cy.diakritis.common.persistence.UserItem;
import com.cy.diakritis.common.security.AuthPrincipal;
import com.cy.diakritis.common.security.Role;
import com.cy.diakritis.iam.service.BadRequestException;
import com.cy.diakritis.iam.service.UserService;
import com.cy.diakritis.iam.web.dto.AdminCreateUserRequest;
import com.cy.diakritis.iam.web.dto.AdminUpdateUserRequest;
import com.cy.diakritis.iam.web.dto.AssignRoleRequest;
import com.cy.diakritis.iam.web.dto.RenameUserRequest;
import com.cy.diakritis.iam.web.dto.ResetPasswordRequest;
import com.cy.diakritis.iam.web.dto.UserView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin user-management console. The whole surface is restricted to ROLE_ADMIN — both coarsely by the
 * security chain ({@code /admin/** → hasRole("ADMIN")}) and method-locally via {@link PreAuthorize}, a
 * belt-and-braces guard so the restriction holds even if the chain matcher is ever relaxed. Responses
 * are {@link UserView} projections that NEVER expose the password hash.
 */
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "List all users", description = "ADMIN only. Returns every user (password hash omitted).")
    @GetMapping
    public List<UserView> list() {
        return userService.listUsers().stream().map(UserView::from).toList();
    }

    @Operation(summary = "Get a user by username", description = "ADMIN only. 404 if the user does not exist.")
    @GetMapping("/{username}")
    public UserView get(@PathVariable("username") String username) {
        return UserView.from(userService.getUser(username));
    }

    @Operation(summary = "Create a user",
            description = "ADMIN only. Creates a user with a BCrypt-hashed password. 409 if the username is taken.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody AdminCreateUserRequest request) {
        Role role = UserService.parseRole(request.role()).orElse(Role.CUSTOMER);
        UserItem user = userService.createUser(request.username(), request.password(), role, request.accountId());
        return UserView.from(user);
    }

    @Operation(summary = "Update a user",
            description = "ADMIN only. Partial update of role / enabled / accountId (null fields unchanged). "
                    + "409 if it would demote/disable the calling admin or the last enabled admin.")
    @PutMapping("/{username}")
    public UserView update(@PathVariable("username") String username,
                           @RequestBody AdminUpdateUserRequest request,
                           @AuthenticationPrincipal AuthPrincipal caller) {
        Role role = UserService.parseRole(request.role()).orElse(null);
        UserItem user = userService.updateUser(
                callerUsername(caller), username, role, request.enabled(), request.accountId());
        return UserView.from(user);
    }

    @Operation(summary = "Assign a user's role",
            description = "ADMIN only. Replaces the user's primary role. 409 if it would demote the "
                    + "calling admin or the last enabled admin.")
    @PostMapping("/{username}/roles")
    public UserView assignRole(@PathVariable("username") String username,
                               @Valid @RequestBody AssignRoleRequest request,
                               @AuthenticationPrincipal AuthPrincipal caller) {
        Role role = UserService.parseRole(request.role())
                .orElseThrow(() -> new BadRequestException("role is required"));
        return UserView.from(userService.assignRole(callerUsername(caller), username, role));
    }

    @Operation(summary = "Reset a user's password",
            description = "ADMIN only. Sets a new BCrypt-hashed password. 404 if the user does not exist.")
    @PostMapping("/{username}/password")
    public UserView resetPassword(@PathVariable("username") String username,
                                  @Valid @RequestBody ResetPasswordRequest request) {
        return UserView.from(userService.resetPassword(username, request.password()));
    }

    @Operation(summary = "Rename a user",
            description = "ADMIN only. Re-keys the user to a new username (must be free; 409 if taken). "
                    + "Invalidates the user's existing refresh tokens.")
    @PostMapping("/{username}/username")
    public UserView rename(@PathVariable("username") String username,
                           @Valid @RequestBody RenameUserRequest request) {
        return UserView.from(userService.rename(username, request.newUsername()));
    }

    @Operation(summary = "Disable a user",
            description = "ADMIN only. Disabled users cannot log in (401). 409 if it would disable the "
                    + "calling admin or the last enabled admin.")
    @PostMapping("/{username}/disable")
    public UserView disable(@PathVariable("username") String username,
                            @AuthenticationPrincipal AuthPrincipal caller) {
        return UserView.from(userService.setEnabled(callerUsername(caller), username, false));
    }

    @Operation(summary = "Enable a user", description = "ADMIN only. Re-enables a previously disabled user.")
    @PostMapping("/{username}/enable")
    public UserView enable(@PathVariable("username") String username,
                           @AuthenticationPrincipal AuthPrincipal caller) {
        return UserView.from(userService.setEnabled(callerUsername(caller), username, true));
    }

    @Operation(summary = "Delete a user",
            description = "ADMIN only. 404 if the user does not exist. 409 if it would delete the "
                    + "calling admin or the last enabled admin.")
    @DeleteMapping("/{username}")
    public ResponseEntity<Void> delete(@PathVariable("username") String username,
                                       @AuthenticationPrincipal AuthPrincipal caller) {
        userService.deleteUser(callerUsername(caller), username);
        return ResponseEntity.noContent().build();
    }

    /**
     * The calling admin's username. The access-token subject ({@link AuthPrincipal#userId()}) is the
     * username (see {@code UserService.issueTokens}), which the admin endpoints key on; this is what the
     * service compares against the target to block self-lockout. Null only if the principal is absent.
     */
    private static String callerUsername(AuthPrincipal caller) {
        return caller == null ? null : caller.userId();
    }
}
