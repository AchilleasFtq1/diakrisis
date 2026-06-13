package com.cy.diakritis.bank.web;

import com.cy.diakritis.bank.service.UserService;
import com.cy.diakritis.bank.web.dto.AdminCreateUserRequest;
import com.cy.diakritis.bank.web.dto.AdminUpdateUserRequest;
import com.cy.diakritis.bank.web.dto.AssignRoleRequest;
import com.cy.diakritis.bank.web.dto.UserView;
import com.cy.diakritis.common.persistence.UserItem;
import com.cy.diakritis.common.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
            description = "ADMIN only. Partial update of role / enabled / accountId (null fields unchanged).")
    @PutMapping("/{username}")
    public UserView update(@PathVariable("username") String username,
                           @RequestBody AdminUpdateUserRequest request) {
        Role role = UserService.parseRole(request.role()).orElse(null);
        UserItem user = userService.updateUser(username, role, request.enabled(), request.accountId());
        return UserView.from(user);
    }

    @Operation(summary = "Assign a user's role",
            description = "ADMIN only. Replaces the user's primary role.")
    @PostMapping("/{username}/roles")
    public UserView assignRole(@PathVariable("username") String username,
                               @Valid @RequestBody AssignRoleRequest request) {
        Role role = UserService.parseRole(request.role())
                .orElseThrow(() -> new com.cy.diakritis.bank.service.BadRequestException("role is required"));
        return UserView.from(userService.assignRole(username, role));
    }

    @Operation(summary = "Disable a user", description = "ADMIN only. Disabled users cannot log in (403).")
    @PostMapping("/{username}/disable")
    public UserView disable(@PathVariable("username") String username) {
        return UserView.from(userService.setEnabled(username, false));
    }

    @Operation(summary = "Enable a user", description = "ADMIN only. Re-enables a previously disabled user.")
    @PostMapping("/{username}/enable")
    public UserView enable(@PathVariable("username") String username) {
        return UserView.from(userService.setEnabled(username, true));
    }

    @Operation(summary = "Delete a user", description = "ADMIN only. 404 if the user does not exist.")
    @DeleteMapping("/{username}")
    public ResponseEntity<Void> delete(@PathVariable("username") String username) {
        userService.deleteUser(username);
        return ResponseEntity.noContent().build();
    }
}
