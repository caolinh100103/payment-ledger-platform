# ADR 0014: One trace from the HTTP request through the outbox to the consumers; JSON logs in ECS

**Status:** Accepted (Phase 5)

## Context

A payment touches three services and two asynchronous hops: the core commits the transfer and its events to the
outbox, a relay publishes them to Kafka later (possibly from another instance, possibly hours later after a broker
outage), and the audit and notification services consume them. When a customer says "I paid at 15:15 and got no
SMS", support needs to follow that one payment through all of it.

Off-the-shelf tracing breaks at the outbox. Instrumented HTTP and Kafka clients propagate the trace context from
thread to thread and over the wire, but the outbox is a database table: the request thread writes a row and ends,
and the relay that reads it runs in its own scheduled thread with no trace at all. Each publication would start a
new, unrelated trace.

## Decision

### The trace id is the correlation id

OpenTelemetry through Micrometer Tracing (Spring Boot's `spring-boot-starter-opentelemetry`) in all three services,
with W3C Trace Context (`traceparent`) propagation. No home-made `X-Correlation-ID`: the trace id already exists on
every request, is propagated by every instrumented client, and is what tracing backends index.

### Carrying the trace across the outbox

1. `Outbox.append` stamps each event with the trace context of the request that writes it, as the CloudEvents
   **Distributed Tracing extension** attributes `traceparent` and `tracestate` (OpenTelemetry's CloudEvents
   conventions use this extension for the event's creation context). Events written outside any trace (startup,
   scheduled jobs) simply carry none.
2. The relay starts a `PRODUCER` span **as a child of that stored context**, whenever it runs, and writes the span's
   own context into the Kafka record's `traceparent` header. Debezium's outbox event router does the same with a
   `tracingspancontext` column.
3. The consumers' Kafka listener observation continues the trace from the header; forwards to retry and dead-letter
   topics get spans of their own.

The relay's own polls (five a second, mostly idle) and actuator scrapes are excluded from tracing with
`ObservationPredicate`s, so the trace store holds requests, not noise.

Because the context is in the event itself, it is also in the audit trail, which stores events verbatim: an auditor
reading a record can open the trace, and the logs, of the request that caused it.

### Ids handed to the caller

Every response carries:

- `X-Trace-Id`: the trace id. Quoted to support, one search finds everything that happened because of the request.
- `x-fapi-interaction-id`: the correlation header of the Financial-grade API profiles used by open banking (UK Open
  Banking, Brazil Open Finance, Australia CDR). The client's own UUID is echoed back; without one, a new UUID is
  returned. It is recorded in the logs and on the request's span, so the bank's id leads to our trace. A value that
  is not a UUID is replaced, never reflected into headers and logs (log and header injection).

A client that sends a `traceparent` joins the trace it started. Both headers are set by a servlet filter that runs
before Spring Security, so a 401 carries them too.

### Structured logs in Elastic Common Schema

Spring Boot's structured logging writes one JSON object per line in ECS: `@timestamp`, `log.level`, `service.name`,
`message`, plus `trace.id` and `span.id` (renamed from Micrometer's MDC keys to their ECS names, so Kibana and
Elastic APM link log lines to traces) and `fapi.interaction_id`. Key events add fields with SLF4J's key-value API:
every committed money movement logs one line with `transfer.id`, `transfer.type`, `transfer.status`,
`transfer.failure_code` and `event.duration`. Logs carry ids and outcomes only: amounts, descriptions and owners stay
in the ledger and the audit trail. `LOG_FORMAT=` (empty) switches a developer's terminal back to plain text.

### Sampling and export

Spans are exported over OTLP/HTTP; locally to Jaeger (in-memory). Sampling is 100% here
(`TRACING_SAMPLING_PROBABILITY`). Trace ids are generated and propagated whether or not a trace is sampled, so logs
and `X-Trace-Id` work at any rate; production would keep a fraction, or let an OpenTelemetry Collector keep the slow
and failed traces (tail sampling).

## Alternatives considered

- **A custom correlation id header** propagated by hand through HTTP, the outbox and Kafka. Duplicates what W3C
  Trace Context and OpenTelemetry already do, and no tool would understand it.
- **A separate outbox column for the trace context** (Debezium's layout). Equivalent; putting it in the CloudEvent
  also gives it to consumers that ignore Kafka headers and to the audit trail, and needs no migration. The relay
  reads it with `payload ->> 'traceparent'`.
- **Linking instead of parenting.** OpenTelemetry's messaging conventions allow the consumer span to only *link* to
  the creation context. Parenting gives one trace per payment, which is what support asks for; the delay between
  the request and the publication is still visible as a gap in that trace.
- **Grafana Tempo instead of Jaeger.** Same OTLP input. Jaeger's all-in-one image runs with no configuration, and
  Grafana reads it as a data source for exemplar links.
- **Logs to Loki or Elasticsearch in the local stack.** The JSON lines are ready for any shipper; adding a log
  pipeline to the demo stack is left for the deployment (Phase 6).

## Consequences

- An event's trace can stay open for as long as Kafka is down: the publish span starts when the relay finally runs,
  so the trace shows the real delay rather than hiding it.
- Every published event costs one extra span; at 100% sampling that is the bulk of the trace volume.
- Plain-text logs (`LOG_FORMAT=`) show the trace and span ids but not the key-value fields; JSON carries them all.
  Tests run with the JSON format, as production does.
