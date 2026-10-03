package com.payledger.common.idempotency;

import com.payledger.common.idempotency.IdempotencyStore.Claimed;
import com.payledger.common.idempotency.IdempotencyStore.ClaimResult;
import com.payledger.common.idempotency.IdempotencyStore.Existing;
import com.payledger.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Makes a money-moving POST safe to retry, following the IETF "Idempotency-Key HTTP Header Field" draft
 * and Stripe's semantics:
 *
 * <ul>
 *   <li>missing or malformed key → 400</li>
 *   <li>same key, same request, completed → the stored response, byte for byte, with
 *       {@code Idempotent-Replayed: true}</li>
 *   <li>same key, different request → 422 {@code IDEMPOTENCY_KEY_REUSED}</li>
 *   <li>same key while the first request is still running → 409 {@code IDEMPOTENCY_KEY_IN_PROGRESS}</li>
 * </ul>
 *
 * <p>Keys are scoped to the caller, as at Stripe: two clients that happen to pick the same key never see each
 * other's responses.
 *
 * <p>The response is stored in the <em>same</em> database transaction as the operation, so a crash can
 * never leave a committed transfer without its stored response (which would let a retry execute it twice).
 * Only outcomes that left a trace (a COMPLETED or FAILED transfer) are stored. An exception means nothing
 * was committed, so the key is released and the client may retry with it.
 */
@Component
public class IdempotencyHandler {

    public static final String HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern KEY_FORMAT = Pattern.compile("^[\\x21-\\x7E]{1,255}$");

    private final IdempotencyStore store;
    private final CurrentActor currentActor;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate operationTx;
    private final TransactionTemplate claimTx;
    private final Duration lease;
    private final Duration retention;

    public IdempotencyHandler(IdempotencyStore store, CurrentActor currentActor, JsonMapper jsonMapper,
                              PlatformTransactionManager txManager,
                              @Value("${payledger.idempotency.lease:30s}") Duration lease,
                              @Value("${payledger.idempotency.retention:24h}") Duration retention) {
        this.store = store;
        this.currentActor = currentActor;
        // Same output as Spring MVC: problem "properties" are flattened into the top-level JSON object.
        this.jsonMapper = jsonMapper.rebuild().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
        this.operationTx = new TransactionTemplate(txManager);
        this.claimTx = new TransactionTemplate(txManager);
        this.claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lease = lease;
        this.retention = retention;
    }

    /**
     * @param requestBody the validated request; together with method and path it identifies the request
     * @param operation   performs the operation and builds its response; runs in a transaction that the
     *                    services it calls join
     */
    public ResponseEntity<String> execute(String key, HttpServletRequest request, Object requestBody,
                                          Supplier<ResponseEntity<?>> operation) {
        validate(key);
        String scope = currentActor.get().name();
        String requestHash = fingerprint(request.getMethod(), request.getRequestURI(), requestBody);

        ClaimResult claim = claimTx.execute(status -> store.claim(scope, key, requestHash, lease, retention));
        return switch (claim) {
            case Existing existing -> replay(existing, requestHash);
            case Claimed claimed -> executeClaimed(scope, key, claimed, operation);
        };
    }

    private ResponseEntity<String> executeClaimed(String scope, String key, Claimed claimed,
                                                  Supplier<ResponseEntity<?>> operation) {
        try {
            StoredResponse response = operationTx.execute(status -> {
                StoredResponse result = serialize(operation.get());
                if (!store.complete(scope, key, claimed.token(), result)) {
                    // Our lease expired and another request took the key over: roll back our work.
                    throw inProgress();
                }
                return result;
            });
            return toHttp(response, false);
        } catch (RuntimeException ex) {
            try {
                claimTx.executeWithoutResult(status -> store.release(scope, key, claimed.token()));
            } catch (RuntimeException releaseFailure) {
                // The lease expires on its own; the original error is what the client needs to see.
                ex.addSuppressed(releaseFailure);
            }
            throw ex;
        }
    }

    private ResponseEntity<String> replay(Existing existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request");
        }
        if (!existing.completed()) {
            throw inProgress();
        }
        return toHttp(existing.response(), true);
    }

    private static IdempotencyException inProgress() {
        return new IdempotencyException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS",
                "A request with this Idempotency-Key is still being processed; retry shortly");
    }

    private static void validate(String key) {
        if (key == null || key.isEmpty()) {
            throw new IdempotencyException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
                    "The " + HEADER + " header is required for this operation");
        }
        if (!KEY_FORMAT.matcher(key).matches()) {
            throw new IdempotencyException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "The " + HEADER + " header must be 1-255 visible ASCII characters, e.g. a UUID");
        }
    }

    private String fingerprint(String method, String path, Object requestBody) {
        String canonical = method + " " + path + "\n" + jsonMapper.writeValueAsString(requestBody);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private StoredResponse serialize(ResponseEntity<?> response) {
        var location = response.getHeaders().getLocation();
        return new StoredResponse(response.getStatusCode().value(), jsonMapper.writeValueAsString(response.getBody()),
                location == null ? null : location.toString());
    }

    private static ResponseEntity<String> toHttp(StoredResponse response, boolean replayed) {
        HttpStatus status = HttpStatus.valueOf(response.status());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(status.isError() ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON);
        if (response.location() != null) {
            builder.header(HttpHeaders.LOCATION, response.location());
        }
        if (replayed) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(response.body());
    }
}
