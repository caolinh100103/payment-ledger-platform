// Load test of the money path: POST /api/v1/transfers, each one a database transaction that locks two accounts,
// posts two ledger entries and writes two outbox events. See loadtest/README.md for how to run it and the results.
//
// Three scenarios, one after the other:
//   warmup       - 30 s of light load so the JIT compiler and the connection pools are warm; not reported
//   spread       - transfers between random pairs of 100 accounts: little lock contention, the platform's throughput
//   hot_account  - every transfer pays into one merchant account: all of them queue for the same row lock (ADR 0004)
//
// Runs well within the 5-minute access token lifetime, so no refresh is needed.
import { check, fail } from 'k6'
import http from 'k6/http'
import { Counter } from 'k6/metrics'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'
const ADMIN_USERNAME = __ENV.ADMIN_USERNAME || 'admin'
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'dev-only bootstrap passphrase'
const CUSTOMERS = Number(__ENV.CUSTOMERS || 100)
const SPREAD_RATE = Number(__ENV.SPREAD_RATE || 300)
const HOT_RATE = Number(__ENV.HOT_RATE || 100)
const PASSWORD = 'correct horse battery staple'
const STARTING_BALANCE = 1_000_000_000 // VND, enough that no transfer of the test is refused

const completed = new Counter('transfers_completed')
const rejected = new Counter('transfers_rejected')

export const options = {
  setupTimeout: '3m',
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      exec: 'spread',
    },
    spread: {
      executor: 'constant-arrival-rate',
      rate: SPREAD_RATE,
      timeUnit: '1s',
      duration: '60s',
      startTime: '35s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      exec: 'spread',
    },
    hot_account: {
      executor: 'constant-arrival-rate',
      rate: HOT_RATE,
      timeUnit: '1s',
      duration: '30s',
      startTime: '100s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      exec: 'hotAccount',
    },
  },
  thresholds: {
    // Every transfer answered, none lost to a lock timeout or a server error.
    'http_req_failed{name:transfer,scenario:spread}': ['rate<0.01'],
    'http_req_failed{name:transfer,scenario:hot_account}': ['rate<0.01'],
    'http_req_duration{name:transfer,scenario:spread}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{name:transfer,scenario:hot_account}': ['p(99)<3000'],
    // Declared so the summary has a count per scenario.
    'http_reqs{name:transfer,scenario:spread}': ['count>0'],
    'http_reqs{name:transfer,scenario:hot_account}': ['count>0'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

function post(path, body, headers) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', ...headers },
    tags: { name: 'setup' },
  })
}

function token(username, password) {
  const response = post('/api/v1/auth/login', { username, password })
  if (response.status !== 200) {
    fail(`cannot sign in as ${username}: ${response.status} ${response.body}`)
  }
  return response.json('accessToken')
}

/** Customers with a funded VND account each, and a merchant account to pay into. */
export function setup() {
  const admin = token(ADMIN_USERNAME, ADMIN_PASSWORD)
  const key = post('/api/v1/api-keys', { name: 'k6 partner bank', scopes: ['deposits:write'] },
    { Authorization: `Bearer ${admin}` }).json('key')
  const run = crypto.randomUUID().slice(0, 8)

  const customers = []
  for (let i = 0; i <= CUSTOMERS; i++) {
    const username = `k6-${run}-${i}`
    const signup = post('/api/v1/auth/signup', { username, password: PASSWORD })
    if (signup.status !== 201) {
      fail(`sign-up failed (is the rate limit off?): ${signup.status} ${signup.body}`)
    }
    const accessToken = token(username, PASSWORD)
    const account = post('/api/v1/accounts', { currency: 'VND' }, { Authorization: `Bearer ${accessToken}` })
      .json('id')
    post('/api/v1/deposits', { accountId: account, amount: STARTING_BALANCE, currency: 'VND' },
      { 'X-API-Key': key, 'Idempotency-Key': crypto.randomUUID() })
    customers.push({ token: accessToken, account })
  }
  // The last one is the merchant of the hot_account scenario.
  return { customers: customers.slice(0, CUSTOMERS), merchant: customers[CUSTOMERS].account }
}

function transfer(from, to) {
  const response = http.post(`${BASE_URL}/api/v1/transfers`, JSON.stringify({
    sourceAccountId: from.account,
    destinationAccountId: to,
    amount: 1000 + Math.floor(Math.random() * 99_000),
    currency: 'VND',
    description: 'k6',
  }), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${from.token}`,
      'Idempotency-Key': crypto.randomUUID(),
    },
    tags: { name: 'transfer' },
  })
  const ok = check(response, { 'transfer completed (201)': (r) => r.status === 201 })
  if (ok) {
    completed.add(1)
  } else if (response.status === 422) {
    rejected.add(1)
  }
}

function pick(customers) {
  return customers[Math.floor(Math.random() * customers.length)]
}

export function spread(data) {
  const from = pick(data.customers)
  let to = pick(data.customers)
  while (to.account === from.account) {
    to = pick(data.customers)
  }
  transfer(from, to.account)
}

export function hotAccount(data) {
  transfer(pick(data.customers), data.merchant)
}

/** The numbers for loadtest/README.md, per scenario. */
export function handleSummary(data) {
  const metric = (name, scenario) => data.metrics[`${name}{name:transfer,scenario:${scenario}}`]?.values
  const ms = (value) => `${Math.round(value)} ms`
  const lines = [
    '| Scenario | Offered | Completed/s | p50 | p95 | p99 | Max | Failed |',
    '|---|---|---|---|---|---|---|---|',
  ]
  for (const [scenario, rate, seconds] of [['spread', SPREAD_RATE, 60], ['hot_account', HOT_RATE, 30]]) {
    const duration = metric('http_req_duration', scenario)
    const requests = metric('http_reqs', scenario)
    const failed = metric('http_req_failed', scenario)
    if (duration && requests && failed) {
      lines.push(`| ${scenario} | ${rate}/s | ${(requests.count * (1 - failed.rate) / seconds).toFixed(0)} | `
        + `${ms(duration.med)} | ${ms(duration['p(95)'])} | ${ms(duration['p(99)'])} | ${ms(duration.max)} | `
        + `${(failed.rate * 100).toFixed(2)} % |`)
    }
  }
  const table = lines.join('\n') + '\n'
  const checks = data.metrics.checks ? (data.metrics.checks.values.rate * 100).toFixed(2) : '?'
  const done = data.metrics.transfers_completed ? data.metrics.transfers_completed.values.count : 0
  return {
    stdout: `\n${table}\ntransfers completed: ${done}, checks passed: ${checks} %\n`,
    '/results/summary.json': JSON.stringify(data, null, 2),
    '/results/summary.md': table,
  }
}
