package com.example.shop.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Skips actuator requests, so tenant.id is NOT guaranteed in MDC for every endpoint. */
@Component
public class TenantFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put("tenantId", request.getHeader("X-Tenant")); // expect: MDC_NOT_CLOSEABLE, MDC_KEY_ALIAS
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("tenantId");
        }
    }
}
