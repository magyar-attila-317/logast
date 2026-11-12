package dev.logast.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Outermost filter: every request starts with an empty MDC and leaves an empty MDC behind, whatever the code in
 * between did. Leftovers from earlier work on the pooled thread and keys the request forgot to remove are reported
 * once each ({@link MdcHygiene}), so the leak can be fixed at its source.
 */
public class MdcHygieneFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> before = MDC.getCopyOfContextMap();
        if (before != null && !before.isEmpty()) {
            MdcHygiene.report("earlier work on this request thread", MdcHygiene.leftovers(before));
            MDC.clear();
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MdcHygiene.report("an HTTP request", MdcHygiene.leftovers(MDC.getCopyOfContextMap()));
            MDC.clear();
        }
    }
}
