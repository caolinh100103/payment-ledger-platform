#!/usr/bin/env bash
# Demo: Kafka goes down, payments keep working, and no event is lost.
#
# Prerequisites: the infrastructure (infra/docker-compose.yml) and all three services are running:
#   backend (8080, with PAYLEDGER_ADMIN_PASSWORD set), services/audit-service (8082),
#   services/notification-service (8083).
# Needs only bash, curl and docker.
set -euo pipefail
source "$(dirname "$0")/common.sh"

TRANSFERS=${TRANSFERS:-5}
run=$(uuid | cut -c1-8)

step "Sign up Alice and Bob, open their accounts, and top up Alice with 1,000,000 VND from the bank"
admin=$(admin_token)
bank=$(bank_key "$admin")
bob_id=$(sign_up "bob-$run")
sign_up "alice-$run" > /dev/null
alice_token=$(token "alice-$run")
bob_token=$(token "bob-$run")
alice=$(open_account "$alice_token")
bob=$(open_account "$bob_token")
deposit "$bank" "$alice" 1000000 > /dev/null
echo "alice=$alice  bob=$bob"
sleep 2

step "Stop Kafka"
"${COMPOSE[@]}" stop kafka

step "Alice makes $TRANSFERS transfers while Kafka is down"
transfer_ids=()
for i in $(seq 1 "$TRANSFERS"); do
  start=$(date +%s%N)
  response=$(transfer "$alice_token" "$alice" "$bob" 50000 "Tiền cơm $i" | body)
  ms=$(( ($(date +%s%N) - start) / 1000000 ))
  id=$(echo "$response" | field id)
  transfer_ids+=("$id")
  echo "transfer $i: $(echo "$response" | field status) in ${ms} ms  ($id)"
done
echo "Bob's balance: $(json GET "$CORE/api/v1/accounts/$bob" "Authorization: Bearer $bob_token" | body | field balance) VND"

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

step "Bob, signed in, sees an SMS for every transfer"
sleep 5
json GET "$NOTIFICATIONS/api/v1/notifications" "Authorization: Bearer $bob_token" | body \
  | grep -o '"message":"[^"]*"' | sed 's/"message":"\(.*\)"/  \1/'
echo "  (recipient: $bob_id)"

step "Every event is in the audit trail, attributed to Alice, and the chain is intact (read as ADMIN)"
for id in "${transfer_ids[@]}"; do
  trail=$(json GET "$AUDIT/api/v1/audit-events?resourceId=$id" "Authorization: Bearer $admin" | body)
  echo "  $id: $(echo "$trail" | grep -o '"action":"[^"]*"' \
    | sed 's/"action":"com.payledger.transfer.\([a-z]*\)"/\1/' | paste -sd' ' -) by $(echo "$trail" | field actor)"
done
echo "  verification: $(json GET "$AUDIT/api/v1/audit-events/verification" "Authorization: Bearer $admin" | body)"
