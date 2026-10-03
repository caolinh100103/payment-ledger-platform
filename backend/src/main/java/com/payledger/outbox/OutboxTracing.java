package com.payledger.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.common.header.Headers;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Carries a request's trace across the outbox, where it would otherwise end: the event is written by the request
 * thread, but published later by the relay, on another thread and perhaps another instance.
 *
 * <ol>
 *   <li>{@link Outbox#append} stamps the event with the W3C trace context of the request (the CloudEvents
 *       Distributed Tracing extension: {@code traceparent}, {@code tracestate}).</li>
 *   <li>The relay starts a PRODUCER span for the publication as a child of that context, however much later it runs,
 *       and puts the span's context in the Kafka headers.</li>
 *   <li>The consumers' Kafka listeners continue the trace from those headers.</li>
 * </ol>
 *
 * <p>So one trace id leads from the HTTP request to the audit record and the SMS. Debezium's outbox event router
 * does the same with a {@code tracingspancontext} column. Span names and tags follow the OpenTelemetry messaging
 * conventions.
 */
@Component
class OutboxTracing {

    static final String TRACEPARENT = "traceparent";
    static final String TRACESTATE = "tracestate";

    private final Tracer tracer;
    private final Propagator propagator;

    OutboxTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** {@code event} stamped with the current trace context; unchanged if nothing is being traced. */
    <T> CloudEvent<T> stamp(CloudEvent<T> event) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null || event.traceparent() != null) {
            return event;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        String traceparent = carrier.get(TRACEPARENT);
        return traceparent == null ? event : event.withTraceContext(traceparent, carrier.get(TRACESTATE));
    }

    /**
     * Starts the span of one publication, as a child of the context the event was created in (a new trace if it
     * carries none), and writes the span's context into {@code headers}. The caller ends the span on Kafka's answer.
     */
    Span startSend(String topic, String key, UUID eventId, String eventType, String traceparent, String tracestate,
                   Headers headers) {
        Map<String, String> creation = new HashMap<>();
        if (traceparent != null) {
            creation.put(TRACEPARENT, traceparent);
            if (tracestate != null) {
                creation.put(TRACESTATE, tracestate);
            }
        }
        Span span = propagator.extract(creation, Map::get)
                .name("send " + topic)
                .kind(Span.Kind.PRODUCER)
                .remoteServiceName("kafka")
                .tag("messaging.system", "kafka")
                .tag("messaging.operation.type", "send")
                .tag("messaging.destination.name", topic)
                .tag("messaging.kafka.message.key", key)
                .tag("messaging.message.id", eventId.toString())
                .tag("cloudevents.event_id", eventId.toString())
                .tag("cloudevents.event_type", eventType)
                .start();
        propagator.inject(span.context(), headers, (carrier, name, value) -> {
            carrier.remove(name);
            carrier.add(name, value.getBytes(StandardCharsets.UTF_8));
        });
        return span;
    }
}
