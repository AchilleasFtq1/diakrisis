package com.cy.demobank.client.dto;

/** Credentials POSTed to {@code /auth/login}: {@code {username, password}}. */
public record LoginRequest(String username, String password) {
}
