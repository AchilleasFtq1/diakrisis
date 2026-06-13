package com.cy.diakritis.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Renders a stable {@code 403 {"error":"FORBIDDEN"}} JSON body when an authenticated principal lacks
 * the authority required for an endpoint (e.g. a CUSTOMER token hitting {@code /admin/**}). Keeps the
 * coarse chain-level 403 consistent in shape with the apps' {@code @RestControllerAdvice} envelope.
 * Never produces a 500.
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private static final String FORBIDDEN_BODY = "{\"error\":\"FORBIDDEN\"}";

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(FORBIDDEN_BODY);
    }
}
