package com.cy.diakritis.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Renders a stable {@code 401 {"error":"UNAUTHENTICATED"}} JSON body when an unauthenticated request
 * reaches a protected endpoint, instead of Spring Security's default HTML/redirect behaviour. Kept
 * consistent in shape with the apps' {@code @RestControllerAdvice} error envelope ({@code {"error":..}})
 * so callers see one error format. Never produces a 500.
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String UNAUTHENTICATED_BODY = "{\"error\":\"UNAUTHENTICATED\"}";

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(UNAUTHENTICATED_BODY);
    }
}
