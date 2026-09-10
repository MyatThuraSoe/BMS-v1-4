package com.bms.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs wall-clock duration for every request under the logger name
 * "REQUEST_TIMING", e.g.:
 *
 *   REQUEST_TIMING: GET /api/sales?page=0 200 -> 42ms
 *
 * Negligible overhead (two nanoTime reads). Grep the log for the slowest
 * lines when hunting latency regressions.
 */
@Component
@Order(2)
public class RequestTimingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("REQUEST_TIMING");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (log.isInfoEnabled()) {
                String uri = request.getRequestURI();
                if (request.getQueryString() != null) {
                    uri += "?" + request.getQueryString();
                }
                log.info("{} {} {} -> {}ms",
                        request.getMethod(), uri, response.getStatus(), ms);
            }
        }
    }
}