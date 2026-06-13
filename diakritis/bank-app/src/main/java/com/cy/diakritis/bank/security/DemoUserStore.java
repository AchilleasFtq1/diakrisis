package com.cy.diakritis.bank.security;

import com.cy.diakritis.common.security.Role;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory directory of the seeded demo users used by {@code /auth/login}. These subjects line up
 * with the SEED accounts (acc-A/B/C) plus the ops and approver operators. All demo passwords are
 * {@code "demo"}; this is a demonstration credential store, not a production identity provider.
 */
@Component
public class DemoUserStore {

    private static final String DEMO_PASSWORD = "demo";

    private final Map<String, DemoUser> usersByName = new LinkedHashMap<>();

    public DemoUserStore() {
        register(new DemoUser("customer-A", DEMO_PASSWORD, Role.CUSTOMER, "acc-A"));
        register(new DemoUser("customer-B", DEMO_PASSWORD, Role.CUSTOMER, "acc-B"));
        register(new DemoUser("customer-C", DEMO_PASSWORD, Role.CUSTOMER, "acc-C"));
        // §17 vulnerability-aware friction: owner of the flagged-vulnerable demo account acc-V.
        register(new DemoUser("customer-vuln", DEMO_PASSWORD, Role.CUSTOMER, "acc-V"));
        register(new DemoUser("approver-biz", DEMO_PASSWORD, Role.APPROVER, null));
        register(new DemoUser("ops-user", DEMO_PASSWORD, Role.OPS, null));
    }

    private void register(DemoUser user) {
        usersByName.put(user.username(), user);
    }

    /**
     * @return the matching user iff the username exists and the password matches, else empty.
     */
    public Optional<DemoUser> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        DemoUser user = usersByName.get(username);
        if (user == null || !user.password().equals(password)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }
}
