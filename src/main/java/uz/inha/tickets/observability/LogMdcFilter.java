package uz.inha.tickets.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-request MDC enrichment (spec §12.2, §27.5).
 *
 * Extracts trace context from the W3C {@code traceparent} header when present (OTel agent
 * propagates it), falls back to nginx-supplied {@code X-Request-Id}, or generates a new id so
 * every log line and ProblemDetail response always carries something correlatable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LogMdcFilter extends OncePerRequestFilter {

    static final String TRACE = "trace_id";
    static final String SPAN = "span_id";
    static final String REQ = "request_id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain fc)
        throws ServletException, IOException {
        String trace = extractTraceId(req.getHeader("traceparent"));
        if (trace == null) trace = req.getHeader("X-Request-Id");
        if (trace == null || trace.isBlank()) trace = UUID.randomUUID().toString().replace("-", "");

        String span = extractSpanId(req.getHeader("traceparent"));
        String reqId = req.getHeader("X-Request-Id");
        if (reqId == null || reqId.isBlank()) reqId = trace;

        try {
            MDC.put(TRACE, trace);
            if (span != null) MDC.put(SPAN, span);
            MDC.put(REQ, reqId);
            res.setHeader("X-Request-Id", reqId);
            res.setHeader("X-Trace-Id", trace);
            fc.doFilter(req, res);
        } finally {
            MDC.remove(TRACE);
            MDC.remove(SPAN);
            MDC.remove(REQ);
        }
    }

    /** {@code traceparent: 00-{trace-id}-{span-id}-{flags}}. */
    static String extractTraceId(String traceparent) {
        if (traceparent == null) return null;
        String[] p = traceparent.split("-");
        return p.length >= 2 && p[1].length() == 32 ? p[1] : null;
    }

    static String extractSpanId(String traceparent) {
        if (traceparent == null) return null;
        String[] p = traceparent.split("-");
        return p.length >= 3 && p[2].length() == 16 ? p[2] : null;
    }
}
