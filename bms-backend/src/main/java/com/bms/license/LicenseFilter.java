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

        // Allow static files, license endpoints, the first-admin registration (which
        // is the ONLY self-service path on a brand-new, unlicensed install — the
        // comment below documents that /api/setup/** was exempted here but has no
        // controller, and registerFirstAdmin() guards itself with count()>0).
        //
        // /api/data/export is the "escape hatch" for a lost/expired license: an
        // installed ADMIN can still pull their JSON backup. It is safe to exempt
        // from the license gate because the endpoint itself still requires a valid
        // ADMIN JWT via @PreAuthorize("hasRole('ADMIN')").
        //
        // /api/health is the frontend heartbeat used to detect a dead server.
        // It must answer even on an unlicensed install, otherwise the offline
        // banner would appear on a healthy server during a license lapse. The
        // endpoint returns only status/timestamp and is already permitAll in
        // SecurityConfig, so no business data is exposed.
        boolean open = !path.startsWith("/api/")
                || path.startsWith("/api/license")
                || path.startsWith("/api/setup")
                || path.startsWith("/api/auth/register-first-admin")
                || path.equals("/api/health")
                || path.equals("/api/data/export");

        if (open || licenseService.isLicensed()) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"LICENSE_REQUIRED\"}");
        }
    }
}