#!/usr/bin/env bash
# Demo: following one payment through three services by its trace id, then the platform's numbers.
#
# Prerequisites: the infrastructure (infra/docker-compose.yml, with Jaeger, Prometheus and Grafana) and all three
# services are running, the core with PAYLEDGER_ADMIN_PASSWORD set (see scripts/common.sh). Needs bash and curl.
set -euo pipefail
source "$(dirname "$0")/common.sh"

JAEGER=${JAEGER_URL:-http://localhost:16686}
PROMETHEUS=${PROMETHEUS_URL:-http://localhost:9090}
GRAFANA=${GRAFANA_URL:-http://localhost:3000}

run=$(uuid | cut -c1-8)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

header() { tr -d '\r' < "$1" | sed -n "s/^$2: //Ip" | head -1; }
# prom QUERY: one "labels value" line per series, from Prometheus' instant query API.
prom() {
  local out
  out=$(curl -s "$PROMETHEUS/api/v1/query" --data-urlencode "query=$1" \
    | grep -o '"metric":{[^}]*},"value":\[[^]]*\]' \
    | sed -E 's/"metric":\{([^}]*)\},"value":\[[^,]*,"([^"]*)"\]/\1 \2/; s/"//g; s/^ /(all) /') || true
  echo "${out:-(no data yet)}" | sed 's/^/  /'
}

step "Alice and Bob sign up; the bank tops Alice up"
admin=$(admin_token)
bank=$(bank_key "$admin")
sign_up "alice-$run" > /dev/null
sign_up "bob-$run" > /dev/null
alice_token=$(token "alice-$run")
bob_token=$(token "bob-$run")
alice=$(open_account "$alice_token")
bob=$(open_account "$bob_token")
deposit "$bank" "$alice" 5000000 > /dev/null

step "Alice pays Bob. Her app sends its own correlation id (x-fapi-interaction-id)"
interaction=$(uuid)
printf '%s' "{\"sourceAccountId\":\"$alice\",\"destinationAccountId\":\"$bob\",\"amount\":250000,\"currency\":\"VND\",\"description\":\"Tien nha thang 10\"}" \
  | curl -s -D "$tmp/headers" -o "$tmp/body" -X POST "$CORE/api/v1/transfers" \
      -H "Authorization: Bearer $alice_token" -H 'Content-Type: application/json' \
      -H "Idempotency-Key: $(uuid)" -H "x-fapi-interaction-id: $interaction" --data-binary @-
transfer_id=$(field id < "$tmp/body")
trace_id=$(header "$tmp/headers" X-Trace-Id)
echo "  transfer               $transfer_id"
echo "  x-fapi-interaction-id  $(header "$tmp/headers" x-fapi-interaction-id)  (echoed back)"
echo "  X-Trace-Id             $trace_id  (quote this to support)"

step "The events in the outbox carry the request's trace context (CloudEvents traceparent)"
sql "SELECT event_type || '  ' || (payload ->> 'traceparent') FROM outbox WHERE aggregate_id = '$transfer_id' ORDER BY id" \
  | sed 's/^/  /'

step "Jaeger has one trace across the three services (spans are exported every few seconds)"
for _ in $(seq 1 20); do
  curl -s "$JAEGER/api/traces/$trace_id" > "$tmp/trace"
  services=$(grep -o '"serviceName":"[^"]*"' "$tmp/trace" | sort -u | cut -d'"' -f4 | tr '\n' ' ') || true
  [[ $services == *payledger-core* && $services == *audit-service* && $services == *notification-service* ]] && break
  sleep 2
done
echo "  services: $services"
# The request, each event's publication by the relay, and each consumer's processing (security spans left out).
grep -o '"operationName":"[^"]*"' "$tmp/trace" | cut -d'"' -f4 | grep -E '^http |^send | process$' \
  | sort | uniq -c | sed 's/^ */  /'
echo "  open: $JAEGER/trace/$trace_id"

step "The audit trail recorded the trace id with each event, so an auditor can get back to the request"
json GET "$AUDIT/api/v1/audit-events?resourceId=$transfer_id" "Authorization: Bearer $admin" | body \
  | grep -o "\"action\":\"[^\"]*\"\|00-$trace_id-[0-9a-f]*-[0-9a-f]*" | paste - - | sed 's/"action"://; s/"//g; s/^/  /'

step "Some traffic: 40 payments, 10 rejected (Bob has too little), 10 retried with the same Idempotency-Key"
for i in $(seq 1 40); do transfer "$alice_token" "$alice" "$bob" 1000 "Coffee $i" > /dev/null & done
for i in $(seq 1 10); do transfer "$bob_token" "$bob" "$alice" 900000000 "Too much $i" > /dev/null & done
wait
for i in $(seq 1 10); do
  key=$(uuid)
  body="{\"sourceAccountId\":\"$alice\",\"destinationAccountId\":\"$bob\",\"amount\":500,\"currency\":\"VND\"}"
  for attempt in 1 2; do
    printf '%s' "$body" | curl -s -o /dev/null -X POST "$CORE/api/v1/transfers" -H "Authorization: Bearer $alice_token" \
      -H 'Content-Type: application/json' -H "Idempotency-Key: $key" --data-binary @-
  done
done
echo "  waiting for Prometheus to scrape (every 15 s)..."
sleep 20

step "What Prometheus saw in the last 5 minutes"
echo " movements by outcome:"
prom 'sum by (status, failure_code) (round(increase(payledger_transfers_total{type="TRANSFER"}[5m])))'
echo " repeated idempotency keys:"
prom 'sum by (outcome) (round(increase(payledger_idempotency_requests_total{uri="/api/v1/transfers"}[5m])))'
echo " p99 movement time (seconds):"
prom 'histogram_quantile(0.99, sum by (le) (rate(payledger_transfer_duration_seconds_bucket[5m])))'
echo " outbox backlog, and consumer lag (kafka-exporter):"
prom 'max(payledger_outbox_pending)'
prom 'sum by (consumergroup) (clamp_min(kafka_consumergroup_lag{topic!~".*-(retry-[0-9]+|dlt)"}, 0))'
echo " alerts firing:"
prom 'count(ALERTS{alertstate="firing"}) or vector(0)'

step "Dashboards"
echo "  $GRAFANA/d/payledger-money-movement   (admin / admin)"
echo "  $GRAFANA/d/payledger-events-platform"
