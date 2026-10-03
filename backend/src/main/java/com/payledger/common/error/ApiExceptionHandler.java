package com.payledger.common.error;

import com.payledger.common.idempotency.IdempotencyException;
import com.payledger.security.AuthenticationFailedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.util.List;

/**
 * Renders all errors as RFC 9457 problem details. Every problem carries a {@code code}
 * property so clients can branch on it without parsing messages.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** PostgreSQL {@code lock_not_available}: {@code lock_timeout} expired. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    /** PostgreSQL {@code deadlock_detected}. */
    private static final String DEADLOCK_DETECTED = "40P01";

    private final MeterRegistry meters;

    public ApiExceptionHandler(MeterRegistry meters) {
        this.meters = meters;
        // From zero, so the first deadlock is an increase that alerts, not a new series that starts at 1.
        for (String reason : new String[]{"lock_timeout", "deadlock"}) {
            meters.counter("payledger.lock.failures", "reason", reason);
        }
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty("code", "RESOURCE_NOT_FOUND");
        return problem;
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ProblemDetail handleBusinessRule(BusinessRuleViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setProperty("code", ex.getCode());
        return problem;
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ProblemDetail> handleAuthenticationFailed(AuthenticationFailedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setProperty("code", ex.getCode());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.UNAUTHORIZED);
        if (ex.getRetryAfter() != null) {
            // Whole seconds, rounded up so a client that waits exactly this long is not refused again.
            long seconds = Math.max(1, ex.getRetryAfter().plusMillis(999).toSeconds());
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }
        return response.body(problem);
    }

    @ExceptionHandler(IdempotencyException.class)
    ResponseEntity<ProblemDetail> handleIdempotency(IdempotencyException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("code", ex.getCode());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex.getStatus() == HttpStatus.CONFLICT) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(problem);
    }

    /**
     * A row lock could not be acquired within {@code lock_timeout} (or, should lock ordering ever be broken,
     * PostgreSQL aborted a deadlock). Nothing was committed, so the client can safely retry the same request
     * with the same idempotency key.
     *
     * <p>Counted in {@code payledger_lock_failures_total{reason}}. Timeouts mean contention; a deadlock means the lock
     * ordering of ADR 0004 was broken somewhere, and should never be seen.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockTimeout(PessimisticLockingFailureException ex) {
        meters.counter("payledger.lock.failures", "reason", lockFailureReason(ex)).increment();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "An account involved is busy with another operation; retry shortly");
        problem.setProperty("code", "LOCK_TIMEOUT");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentModification(OptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource was modified concurrently; reload it and retry");
        problem.setProperty("code", "CONCURRENT_MODIFICATION");
        return problem;
    }

    /**
     * Spring MVC's own errors (malformed JSON, failed validation, a missing parameter, an unknown path) get a
     * {@code code} too, so a client never has to fall back to the status. Validation failures list the offending
     * fields in {@code invalidParams}, the extension RFC 9457 uses as its example, for a form to show next to each.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        // The problem is only built by the superclass: Spring's handlers pass a null body for it to fill in.
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                problem.setProperty("code", switch (statusCode.value()) {
                    case 400 -> "INVALID_REQUEST";
                    case 404 -> "RESOURCE_NOT_FOUND";
                    default -> {
                        HttpStatus status = HttpStatus.resolve(statusCode.value());
                        yield status != null ? status.name() : "ERROR";
                    }
                });
            }
            List<Problem.InvalidParam> invalidParams = invalidParams(ex);
            if (!invalidParams.isEmpty()) {
                problem.setProperty("invalidParams", invalidParams);
            }
        }
        return response;
    }

    private static List<Problem.InvalidParam> invalidParams(Exception ex) {
        return switch (ex) {
            case MethodArgumentNotValidException invalid -> invalid.getBindingResult().getAllErrors().stream()
                    .map(error -> new Problem.InvalidParam(
                            error instanceof FieldError field ? field.getField() : error.getObjectName(),
                            error.getDefaultMessage()))
                    .toList();
            case HandlerMethodValidationException invalid -> invalid.getParameterValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream()
                            .map(MessageSourceResolvable::getDefaultMessage)
                            .map(reason -> new Problem.InvalidParam(result.getMethodParameter().getParameterName(),
                                    reason)))
                    .toList();
            default -> List.of();
        };
    }

    private static String lockFailureReason(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return switch (String.valueOf(sql.getSQLState())) {
                    case LOCK_NOT_AVAILABLE -> "lock_timeout";
                    case DEADLOCK_DETECTED -> "deadlock";
                    default -> "other";
                };
            }
        }
        return "other";
    }
}
