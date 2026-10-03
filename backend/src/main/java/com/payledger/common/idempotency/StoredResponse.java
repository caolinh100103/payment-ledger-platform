package com.payledger.common.idempotency;

/** The HTTP response recorded for an idempotency key; {@code location} may be null. */
record StoredResponse(int status, String body, String location) {
}
