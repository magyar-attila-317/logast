package com.example.shop.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put("request.id", UUID.randomUUID().toString()); // expect: MDC_NOT_CLOSEABLE
        MDC.put("traceId", request.getHeader("X-Trace-Id")); // expect: MANUAL_TRACE_MDC
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("request.id");
            MDC.remove("traceId");
        }
    }
}
