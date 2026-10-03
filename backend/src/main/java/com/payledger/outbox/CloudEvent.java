package com.payledger.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * A <a href="https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md">CloudEvents 1.0</a> envelope.
 * It is sent in structured content mode: the whole envelope, serialized as JSON, is the Kafka message value.
 *
 * <p>Besides the standard attributes it carries two extension attributes (CloudEvents requires extension
 * names to be lower-case letters and digits):
 * <ul>
 *   <li>{@code schemaversion}: the version of the {@code data} schema for this {@code type}. Adding a field
 *       keeps the version; removing or changing one bumps it, and consumers reject versions they do not know.</li>
 *   <li>{@code actor}: who caused the event, for the audit trail.</li>
 * </ul>
 *
 * @param subject the aggregate the event is about, e.g. the transfer id
 * @param data    the event payload; its shape is defined by {@code type} and {@code schemaversion}
 */
public record CloudEvent<T>(
        String specversion,
        UUID id,
        String source,
        String type,
        String subject,
        Instant time,
        String datacontenttype,
        int schemaversion,
        String actor,
        T data) {

    public static final String SPEC_VERSION = "1.0";
    /** Kafka {@code content-type} header for structured mode (CloudEvents Kafka protocol binding). */
    public static final String STRUCTURED_CONTENT_TYPE = "application/cloudevents+json; charset=UTF-8";

    public CloudEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(time, "time");
    }

    /** A new event with a random id, timestamped now. */
    public static <T> CloudEvent<T> of(String source, String type, int schemaVersion, String subject, String actor,
                                       T data) {
        // PostgreSQL and most consumers keep microseconds; truncating keeps the value identical everywhere.
        return new CloudEvent<>(SPEC_VERSION, UUID.randomUUID(), source, type, subject,
                Instant.now().truncatedTo(ChronoUnit.MICROS), "application/json", schemaVersion, actor, data);
    }
}
