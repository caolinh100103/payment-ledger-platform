#!/usr/bin/env bash
# Demo: Kafka goes down, payments keep working, and no event is lost.
#
# Prerequisites: the infrastructure (infra/docker-compose.yml) and all three services are running:
#   backend (8080), services/audit-service (8082), services/notification-service (8083).
# Needs only bash, curl and docker.
set -euo pipefail

CORE=${CORE_URL:-http://localhost:8080}
AUDIT=${AUDIT_URL:-http://localhost:8082}
NOTIFICATIONS=${NOTIFICATION_URL:-http://localhost:8083}
COMPOSE=(docker compose -f "$(dirname "$0")/../infra/docker-compose.yml")
TRANSFERS=${TRANSFERS:-5}

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
field() { sed -n "s/.*\"$1\":\"\\{0,1\\}\\([^\",}]*\\).*/\\1/p" | head -1; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || python -c 'import uuid; print(uuid.uuid4())'; }
# The body goes through stdin: on Windows, command-line arguments are re-encoded and lose Vietnamese characters.
post() {
  printf '%s' "$2" | curl -s -X POST "$CORE$1" -H 'Content-Type: application/json; charset=utf-8' \
    -H "Idempotency-Key: $(uuid)" --data-binary @-
}
sql() { "${COMPOSE[@]}" exec -T postgres psql -U payledger -d payledger -tAc "$1"; }

run=$(uuid | cut -c1-8)
alice_owner="alice-$run"
bob_owner="bob-$run"

step "Open two accounts and top up Alice with 1,000,000 VND"
alice=$(curl -s -X POST "$CORE/api/v1/accounts" -H 'Content-Type: application/json' \
  -d "{\"ownerId\":\"$alice_owner\",\"currency\":\"VND\"}" | field id)
bob=$(curl -s -X POST "$CORE/api/v1/accounts" -H 'Content-Type: application/json' \
  -d "{\"ownerId\":\"$bob_owner\",\"currency\":\"VND\"}" | field id)
post /api/v1/deposits "{\"accountId\":\"$alice\",\"amount\":1000000,\"currency\":\"VND\"}" > /dev/null
echo "alice=$alice  bob=$bob"
sleep 2

step "Stop Kafka"
"${COMPOSE[@]}" stop kafka

step "Make $TRANSFERS transfers while Kafka is down"
transfer_ids=()
for i in $(seq 1 "$TRANSFERS"); do
  start=$(date +%s%N)
  response=$(post /api/v1/transfers \
    "{\"sourceAccountId\":\"$alice\",\"destinationAccountId\":\"$bob\",\"amount\":50000,\"currency\":\"VND\",\"description\":\"Tiền cơm $i\"}")
  ms=$(( ($(date +%s%N) - start) / 1000000 ))
  id=$(echo "$response" | field id)
  transfer_ids+=("$id")
  echo "transfer $i: $(echo "$response" | field status) in ${ms} ms  ($id)"
done
echo "Bob's balance: $(curl -s "$CORE/api/v1/accounts/$bob" | field balance) VND"

step "Events are safe in the outbox, waiting for Kafka"
sleep 12  # longer than the producer's delivery.timeout.ms (10 s), so failed attempts show up
sql "SELECT count(*) || ' pending events, ' || COALESCE(sum(attempts), 0) || ' failed publish attempts so far'
     FROM outbox WHERE published_at IS NULL"

step "Start Kafka again"
"${COMPOSE[@]}" start kafka
"${COMPOSE[@]}" up -d --wait kafka > /dev/null 2>&1

step "Wait for the relay to drain the outbox"
for _ in $(seq 1 60); do
  pending=$(sql "SELECT count(*) FROM outbox WHERE published_at IS NULL")
  [ "$pending" = "0" ] && break
  sleep 1
done
echo "pending events: $pending"

step "Bob was notified of every transfer"
sleep 5
curl -s "$NOTIFICATIONS/api/v1/notifications?recipientId=$bob_owner" \
  | grep -o '"message":"[^"]*"' | sed 's/"message":"\(.*\)"/  \1/'

step "Every event is in the audit trail, and the chain is intact"
for id in "${transfer_ids[@]}"; do
  echo "  $id: $(curl -s "$AUDIT/api/v1/audit-events?resourceId=$id" | grep -o '"action":"[^"]*"' \
    | sed 's/"action":"com.payledger.transfer.\([a-z]*\)"/\1/' | paste -sd' ' -)"
done
echo "  verification: $(curl -s "$AUDIT/api/v1/audit-events/verification")"
