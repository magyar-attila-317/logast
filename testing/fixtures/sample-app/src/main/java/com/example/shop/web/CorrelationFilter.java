package com.example.shop.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.opentelemetry.api.trace.Span;
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
        MDC.put("traceId", request.getHeader("X-Trace-Id")); // expect: TRACE_KEY_HOLDS_APP_ID
        MDC.put("spanId", Span.current().getSpanContext().getSpanId()); // expect: MANUAL_TRACE_MDC
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("request.id");
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }
}
