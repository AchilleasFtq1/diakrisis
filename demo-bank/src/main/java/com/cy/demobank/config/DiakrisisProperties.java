package com.cy.demobank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Configurable base-urls for the Diakrisis services demo-bank calls. The API gateway does not exist
 * yet, so by default the IAM and decision services are called DIRECTLY on their own ports. When the
 * gateway is brought up, set {@code diakrisis.gateway.base-url} (e.g. {@code http://localhost:8080})
 * and BOTH {@link #iamBaseUrl()} and {@link #decisionBaseUrl()} resolve to it — a single override
 * repoints every call behind the gateway with no code change.
 */
@ConfigurationProperties(prefix = "diakrisis")
public class DiakrisisProperties {

    private final Gateway gateway = new Gateway();
    private final Iam iam = new Iam();
    private final Decision decision = new Decision();
    private final Auth auth = new Auth();

    public Gateway getGateway() {
        return gateway;
    }

    public Iam getIam() {
        return iam;
    }

    public Decision getDecision() {
        return decision;
    }

    public Auth getAuth() {
        return auth;
    }

    /** The IAM login base-url: the gateway when set, else the direct IAM service base-url. */
    public String iamBaseUrl() {
        return effectiveBaseUrl(iam.getBaseUrl());
    }

    /** The decision base-url: the gateway when set, else the direct decision service base-url. */
    public String decisionBaseUrl() {
        return effectiveBaseUrl(decision.getBaseUrl());
    }

    private String effectiveBaseUrl(String directBaseUrl) {
        String gatewayBaseUrl = gateway.getBaseUrl();
        return StringUtils.hasText(gatewayBaseUrl) ? gatewayBaseUrl.trim() : directBaseUrl;
    }

    public static class Gateway {
        /** When set, overrides BOTH iam + decision base-urls (the single repoint to the gateway). */
        private String baseUrl = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Iam {
        private String baseUrl = "http://localhost:8083";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Decision {
        private String baseUrl = "http://localhost:8081";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Auth {
        /** The password demo-bank uses to log the owning customer in to Diakrisis IAM. */
        private String password = "demo";

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
