#!/usr/bin/env bash
# Helpers shared by the demo scripts. Needs only bash and curl.
#
# The core must run with a bootstrap admin, e.g.
#   PAYLEDGER_ADMIN_PASSWORD='dev-only bootstrap passphrase' ./mvnw spring-boot:run

CORE=${CORE_URL:-http://localhost:8080}
AUDIT=${AUDIT_URL:-http://localhost:8082}
NOTIFICATIONS=${NOTIFICATION_URL:-http://localhost:8083}
ADMIN_USERNAME=${PAYLEDGER_ADMIN_USERNAME:-admin}
ADMIN_PASSWORD=${PAYLEDGER_ADMIN_PASSWORD:-dev-only bootstrap passphrase}
COMPOSE=(docker compose -f "$(dirname "${BASH_SOURCE[0]}")/../infra/docker-compose.yml")

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
field() { sed -n "s/.*\"$1\":\"\\{0,1\\}\\([^\",}]*\\).*/\\1/p" | head -1; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || python -c 'import uuid; print(uuid.uuid4())'; }
sql() { "${COMPOSE[@]}" exec -T postgres psql -U payledger -d payledger -tAc "$1"; }

# json METHOD URL CREDENTIAL-HEADER [BODY]: the body goes through stdin, because on Windows command-line arguments
# are re-encoded and lose Vietnamese characters. Prints the body, then the status code on the last line.
json() {
  local method=$1 url=$2 credential=$3 body=${4:-}
  local args=(-s -X "$method" "$url" -H 'Content-Type: application/json; charset=utf-8' -w '\n%{http_code}')
  [ -n "$credential" ] && args+=(-H "$credential")
  [ "$method" = POST ] && args+=(-H "Idempotency-Key: $(uuid)")
  if [ -n "$body" ]; then
    printf '%s' "$body" | curl "${args[@]}" --data-binary @-
  else
    curl "${args[@]}"
  fi
}
body() { sed '$d'; }
status() { tail -1; }

# sign_up USERNAME -> user id
sign_up() {
  json POST "$CORE/api/v1/auth/signup" "" "{\"username\":\"$1\",\"password\":\"correct horse battery staple\"}" \
    | body | field id
}

# token USERNAME [PASSWORD] -> access token
token() {
  json POST "$CORE/api/v1/auth/login" "" \
    "{\"username\":\"$1\",\"password\":\"${2:-correct horse battery staple}\"}" | body | field accessToken
}

# bank_key ADMIN_TOKEN -> a new API key with the deposits:write scope
bank_key() {
  json POST "$CORE/api/v1/api-keys" "Authorization: Bearer $1" \
    '{"name":"Partner bank (demo)","scopes":["deposits:write"]}' | body | field key
}

# open_account TOKEN -> account id
open_account() {
  json POST "$CORE/api/v1/accounts" "Authorization: Bearer $1" '{"currency":"VND"}' | body | field id
}

# deposit BANK_KEY ACCOUNT AMOUNT
deposit() {
  json POST "$CORE/api/v1/deposits" "X-API-Key: $1" "{\"accountId\":\"$2\",\"amount\":$3,\"currency\":\"VND\"}"
}

# transfer TOKEN FROM TO AMOUNT DESCRIPTION
transfer() {
  json POST "$CORE/api/v1/transfers" "Authorization: Bearer $1" \
    "{\"sourceAccountId\":\"$2\",\"destinationAccountId\":\"$3\",\"amount\":$4,\"currency\":\"VND\",\"description\":\"$5\"}"
}

admin_token() {
  local admin
  admin=$(token "$ADMIN_USERNAME" "$ADMIN_PASSWORD")
  if [ -z "$admin" ]; then
    echo "Cannot sign in as '$ADMIN_USERNAME'. Start the core with PAYLEDGER_ADMIN_PASSWORD set (see scripts/common.sh)." >&2
    exit 1
  fi
  echo "$admin"
}
