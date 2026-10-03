#!/usr/bin/env bash
# Seeds a demo: customers alice and bob with VND accounts and some history, and one staff member per role, all with
# the password "correct horse battery staple". Safe to run again: existing users and accounts are reused.
#
# Prerequisites: the stack is running (docker compose --profile app up -d, or the services from the IDE), the core
# with PAYLEDGER_ADMIN_PASSWORD set. CORE_URL points elsewhere if needed. Needs only bash and curl.
#
# Sign-up and sign-in are limited to 20 requests a minute per address; this script makes about 10.
set -euo pipefail
source "$(dirname "$0")/common.sh"

PASSWORD='correct horse battery staple'

# ensure_user USERNAME ROLE ADMIN_TOKEN: creates the user unless it exists (409 USERNAME_TAKEN).
ensure_user() {
  local out
  if [ "$2" = CUSTOMER ]; then
    out=$(json POST "$CORE/api/v1/auth/signup" "" "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}")
  else
    out=$(json POST "$CORE/api/v1/users" "Authorization: Bearer $3" \
      "{\"username\":\"$1\",\"password\":\"$PASSWORD\",\"role\":\"$2\"}")
  fi
  case $(echo "$out" | status) in
    201) echo "  created $1 ($2)" ;;
    409) echo "  $1 already exists" ;;
    *) echo "  could not create $1: $(echo "$out" | body)" >&2; exit 1 ;;
  esac
}

# account_of TOKEN: the customer's first account, opened if there is none.
account_of() {
  local id
  id=$(json GET "$CORE/api/v1/accounts" "Authorization: Bearer $1" | body | field id)
  [ -n "$id" ] || id=$(open_account "$1")
  echo "$id"
}

balance_of() {
  json GET "$CORE/api/v1/accounts/$2" "Authorization: Bearer $1" | body | field balance
}

step "Staff and customers"
admin=$(admin_token)
ensure_user operator OPERATOR "$admin"
ensure_user auditor AUDITOR "$admin"
ensure_user alice CUSTOMER "$admin"
ensure_user bob CUSTOMER "$admin"

step "Accounts"
alice_token=$(token alice "$PASSWORD")
bob_token=$(token bob "$PASSWORD")
alice=$(account_of "$alice_token")
bob=$(account_of "$bob_token")
echo "  alice: $alice"
echo "  bob:   $bob"

if [ "$(balance_of "$alice_token" "$alice")" = 0 ]; then
  step "The partner bank tops alice up, alice pays bob"
  bank=$(bank_key "$admin")
  deposit "$bank" "$alice" 25000000 > /dev/null
  deposit "$bank" "$bob" 3000000 > /dev/null
  transfer "$alice_token" "$alice" "$bob" 4500000 "Tien nha thang 10" > /dev/null
  transfer "$alice_token" "$alice" "$bob" 350000 "An trua" > /dev/null
  transfer "$bob_token" "$bob" "$alice" 120000 "Tra lai tien cafe" > /dev/null
fi
echo "  alice has $(balance_of "$alice_token" "$alice") VND, bob $(balance_of "$bob_token" "$bob") VND"

step "Sign in to the web app with any of these (password: $PASSWORD)"
echo "  alice, bob (customers) · operator · auditor · $ADMIN_USERNAME (admin, with its own password)"
