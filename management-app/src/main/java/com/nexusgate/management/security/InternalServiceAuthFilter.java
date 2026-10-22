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
        System.out.println("SHOULD NOT FILTER CHECK FROM INTERNAL FILTER");
        String method = request.getMethod();
        String path = request.getRequestURI();
        boolean check = !("POST".equals(request.getMethod())
                && "/api/v1/api-keys/internal/validate"
                .equals(request.getRequestURI()));
        System.out.println("Method : " + method);
        System.out.println("Path : " + path);
        System.out.println("SHOULD NOT FILTER: " + check);
        return check;
       // return !"/api/v1/api-keys/internal/validate"
              //  .equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        System.out.println("INTERNAL FILTER EXECUTING");
        String suppliedSecret = request.getHeader("X-Internal-Secret");
        System.out.println("INTERNAL FILTER :HEADER RECEAVED:" + suppliedSecret);
        if (suppliedSecret == null ||
                !MessageDigest.isEqual(expectedSecret.getBytes(StandardCharsets.UTF_8),
                        suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
            System.out.println("INTERNAL FILTER :HEADER RECEAVED:INVALID OR MISSING");
            response.sendError(
                    HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        System.out.println("INTERNAL FILTER :HEADER RECEAVED:VALID");
        filterChain.doFilter(request, response);
    }
}
