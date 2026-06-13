package com.cy.demobank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the demo-bank — a standalone test consumer of the Diakrisis fraud APIs.
 *
 * <p>It holds demo accounts/payees/deposits in SQLite and, before executing any money action,
 * logs in to Diakrisis IAM, builds an {@code ActionEvent} from the SQLite facts, calls the
 * Diakrisis decision API, and renders the returned verdict. The balance change is applied only
 * when the verdict permits it (ALLOW, or a confirmed CONFIRM).
 */
@SpringBootApplication
public class DemoBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoBankApplication.class, args);
    }
}
