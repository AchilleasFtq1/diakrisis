package com.cy.diakritis.common.security;

/**
 * Per-request carrier of the raw inbound bearer token.
 *
 * <p>bank-app must forward the caller's credential to decision-service. Since the call into
 * decision-service happens deep in the service layer (away from the controller's
 * {@code HttpServletRequest}), we stash the token on a {@link ThreadLocal} populated by
 * {@link JwtAuthFilter} and cleared in its {@code finally} block. With virtual threads enabled
 * the request is pinned to one carrier-managed thread for the duration of the filter chain, so
 * a {@code ThreadLocal} remains the correct request-scoped carrier.
 */
public final class BearerTokenHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private BearerTokenHolder() {
    }

    public static void set(String token) {
        CURRENT.set(token);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
