package com.cy.diakritis.bank.security;

import com.cy.diakritis.bank.repo.UserRepository;
import com.cy.diakritis.bank.service.UserService;
import com.cy.diakritis.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Idempotent startup migration: materializes the demo principals as REAL persisted users (with
 * BCrypt-hashed passwords) the first time bank-app boots against a fresh DynamoDB. Create-if-missing:
 * a username that already exists is left untouched, so reboots and manual edits are preserved.
 *
 * <p>The demo customers / approver / ops subjects keep their original usernames, roles and account
 * bindings (the golden-path and live demos depend on these); their password is {@code "demo"}. A new
 * {@code admin} principal ({@link Role#ADMIN}, password {@code "admin"}) backs the admin console.
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoUserSeeder.class);

    private static final String DEMO_PASSWORD = "demo";
    private static final String ADMIN_PASSWORD = "admin";

    /** The seed directory. Existing usernames/roles/accountIds are NOT changed (golden path depends on them). */
    private static final List<SeedUser> SEED_USERS = List.of(
            new SeedUser("customer-A", DEMO_PASSWORD, Role.CUSTOMER, "acc-A"),
            new SeedUser("customer-B", DEMO_PASSWORD, Role.CUSTOMER, "acc-B"),
            new SeedUser("customer-C", DEMO_PASSWORD, Role.CUSTOMER, "acc-C"),
            // §17 vulnerability-aware friction: owner of the flagged-vulnerable demo account acc-V.
            new SeedUser("customer-vuln", DEMO_PASSWORD, Role.CUSTOMER, "acc-V"),
            new SeedUser("approver-biz", DEMO_PASSWORD, Role.APPROVER, null),
            new SeedUser("ops-user", DEMO_PASSWORD, Role.OPS, null),
            // New ADMIN principal backing the /admin/users console.
            new SeedUser("admin", ADMIN_PASSWORD, Role.ADMIN, null));

    private final UserRepository userRepository;
    private final UserService userService;

    public DemoUserSeeder(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        for (SeedUser seed : SEED_USERS) {
            if (userRepository.existsByUsername(seed.username())) {
                continue;
            }
            userService.register(seed.username(), seed.password(), seed.role(), seed.accountId());
            created++;
        }
        LOG.info("Demo user seed complete: {} created, {} already present",
                created, SEED_USERS.size() - created);
    }

    private record SeedUser(String username, String password, Role role, String accountId) {
    }
}
