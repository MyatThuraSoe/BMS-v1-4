package com.bms.license;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
@RequiredArgsConstructor
public class LicenseFilter extends OncePerRequestFilter {

    private final LicenseService licenseService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Allow static files, license endpoints, and first-admin setup
        boolean open = !path.startsWith("/api/")
                || path.startsWith("/api/license")
                || path.startsWith("/api/setup");

        if (open || licenseService.isLicensed()) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"LICENSE_REQUIRED\"}");
        }
    }
}