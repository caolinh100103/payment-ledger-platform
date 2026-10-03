package com.payledger.common.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every response the ids that find the request again.
 *
 * <ul>
 *   <li>{@code X-Trace-Id}: the W3C trace id of the request. Quoted to support, one search finds the request in the
 *       core's logs and traces, the publication of the events it caused, and their consumption by the audit and
 *       notification services. A client that sends a {@code traceparent} header joins the trace it started.</li>
 *   <li>{@code x-fapi-interaction-id}: the correlation id of the Financial-grade API (FAPI) profiles used by open
 *       banking (UK, Brazil, Australia): the client's own id for the call, echoed back, or a new UUID when it sent
 *       none. It goes into the logs and onto the request's span, so the bank's id finds our trace too.</li>
 * </ul>
 *
 * <p>A client-supplied interaction id is used only if it is a UUID: anything else is replaced rather than reflected
 * into headers and logs. Registered right after Spring's {@link ServerHttpObservationFilter}, which starts the
 * request's span; a servlet filter on purpose, so even a 401 from the security chain carries the ids.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class CorrelationFilter extends OncePerRequestFilter {

    static final String TRACE_ID_HEADER = "X-Trace-Id";
    static final String INTERACTION_ID_HEADER = "x-fapi-interaction-id";
    /** In logs: {@code fapi.interaction_id}, next to {@code trace.id} and {@code span.id}. */
    static final String INTERACTION_ID_KEY = "fapi.interaction_id";

    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final Tracer tracer;

    CorrelationFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String interactionId = interactionId(request.getHeader(INTERACTION_ID_HEADER));
        response.setHeader(INTERACTION_ID_HEADER, interactionId);
        Span span = tracer.currentSpan();
        if (span != null) {
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        }
        // High cardinality: a tag on the request's span, never a metric tag.
        ServerHttpObservationFilter.findObservationContext(request).ifPresent(
                context -> context.addHighCardinalityKeyValue(KeyValue.of(INTERACTION_ID_KEY, interactionId)));

        MDC.put(INTERACTION_ID_KEY, interactionId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(INTERACTION_ID_KEY);
        }
    }

    private static String interactionId(String header) {
        return header != null && UUID_FORMAT.matcher(header).matches()
                ? header.toLowerCase()
                : UUID.randomUUID().toString();
    }
}
