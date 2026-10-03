#!/usr/bin/env bash
# Demo: who may do what, and what happens when someone tries anyway.
#
# Prerequisites: the infrastructure (infra/docker-compose.yml) and all three services are running, the core with
# PAYLEDGER_ADMIN_PASSWORD set (see scripts/common.sh). Needs only bash and curl.
#
# Sign-in, sign-up and refresh are limited to 20 requests a minute per address and this demo makes 17 of them, so
# wait a minute before running it again.
set -euo pipefail
source "$(dirname "$0")/common.sh"

run=$(uuid | cut -c1-8)
show() { local out; out=$(cat); printf '  %s %s\n' "$(echo "$out" | status)" "$(echo "$out" | body | field code)"; }

step "Without a token, nothing but sign-in and sign-up"
json GET "$CORE/api/v1/accounts" "" | show

step "Alice and Mallory sign up; the bank tops Alice up with 1,000,000 VND"
admin=$(admin_token)
bank=$(bank_key "$admin")
sign_up "alice-$run" > /dev/null
bob_id=$(sign_up "bob-$run")
sign_up "mallory-$run" > /dev/null
alice_token=$(token "alice-$run")
mallory_token=$(token "mallory-$run")
alice=$(open_account "$alice_token")
mallory=$(open_account "$mallory_token")
deposit "$bank" "$alice" 1000000 > /dev/null
echo "  alice's account: $alice"

step "Mallory tries to spend from Alice's account: 'not found', and nothing is recorded"
transfer "$mallory_token" "$alice" "$mallory" 1000000 "Cảm ơn" | show
step "... or to read it: the same answer as for an account that does not exist"
json GET "$CORE/api/v1/accounts/$alice" "Authorization: Bearer $mallory_token" | show
step "... or to credit herself as if she were the bank, or to freeze Alice: not her role"
json POST "$CORE/api/v1/deposits" "Authorization: Bearer $mallory_token" \
  "{\"accountId\":\"$mallory\",\"amount\":1000000,\"currency\":\"VND\"}" | show
json POST "$CORE/api/v1/accounts/$alice/freeze" "Authorization: Bearer $mallory_token" | show
echo "  Alice still has $(json GET "$CORE/api/v1/accounts/$alice" "Authorization: Bearer $alice_token" | body | field balance) VND"

step "Mallory guesses Bob's password: the 5th miss locks Bob out, then even the right password is refused"
for i in 1 2 3 4 5; do
  json POST "$CORE/api/v1/auth/login" "" "{\"username\":\"bob-$run\",\"password\":\"guess number $i\"}" | show
done
json POST "$CORE/api/v1/auth/login" "" "{\"username\":\"bob-$run\",\"password\":\"correct horse battery staple\"}" | show

step "Bob calls the hotline; an operator unlocks him"
json POST "$CORE/api/v1/users" "Authorization: Bearer $admin" \
  "{\"username\":\"operator-$run\",\"password\":\"correct horse battery staple\",\"role\":\"OPERATOR\"}" > /dev/null
operator_token=$(token "operator-$run")
json POST "$CORE/api/v1/users/$bob_id/unlock" "Authorization: Bearer $operator_token" | show

step "Bob signs in. His refresh token is copied; Bob refreshes first, then the copy is used"
stolen=$(json POST "$CORE/api/v1/auth/login" "" \
  "{\"username\":\"bob-$run\",\"password\":\"correct horse battery staple\"}" | body | field refreshToken)
bobs_next=$(json POST "$CORE/api/v1/auth/refresh" "" "{\"refreshToken\":\"$stolen\"}" | body | field refreshToken)
json POST "$CORE/api/v1/auth/refresh" "" "{\"refreshToken\":\"$stolen\"}" | show
echo "  The whole session is revoked, Bob's own new token too:"
json POST "$CORE/api/v1/auth/refresh" "" "{\"refreshToken\":\"$bobs_next\"}" | show

step "Mallory fires 150 requests at once: her bucket holds 120 (refilled at 2 a second), the rest get 429"
seq 1 150 | xargs -P 25 -I{} curl -s -o /dev/null -w '%{http_code}\n' "$CORE/api/v1/accounts" \
  -H "Authorization: Bearer $mallory_token" | sort | uniq -c | sed 's/^/  /'
echo "  Retry-After: $(curl -s -D - -o /dev/null "$CORE/api/v1/accounts" -H "Authorization: Bearer $mallory_token" \
  | tr -d '\r' | sed -n 's/^Retry-After: //p') s"

step "Bob's security trail, as the audit service recorded it (read as ADMIN)"
sleep 3
json GET "$AUDIT/api/v1/audit-events?resourceId=$bob_id" "Authorization: Bearer $admin" | body \
  | grep -o '"actor":"[^"]*","action":"[^"]*"' \
  | sed 's/"actor":"\([^"]*\)","action":"com.payledger.\([^"]*\)"/  \2 by \1/'
