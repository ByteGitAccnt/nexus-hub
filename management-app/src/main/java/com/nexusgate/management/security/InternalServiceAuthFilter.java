package com.nexusgate.management.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    private final String expectedSecret;

    public InternalServiceAuthFilter(
            @Value("${nexushub.internal.shared-secret}")
            String expectedSecret) {

        if (expectedSecret == null || expectedSecret.isBlank()) {
            throw new IllegalStateException("Internal service secret must be configured");
        }
        this.expectedSecret = expectedSecret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {

        return !("POST".equals(request.getMethod())
                && "/api/v1/api-keys/internal/validate"
                .equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String suppliedSecret = request.getHeader("X-Internal-Secret");
        if (suppliedSecret == null ||
                !MessageDigest.isEqual(expectedSecret.getBytes(StandardCharsets.UTF_8),
                        suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(
                    HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
