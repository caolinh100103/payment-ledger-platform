package com.payledger.audit;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Spring MVC's own errors (an invalid parameter, an unknown path) as RFC 9457 problems with a {@code code}, the same
 * shape as the core's and as {@link SecurityProblems}. Validation failures name the parameters in
 * {@code invalidParams}.
 */
@RestControllerAdvice
class ApiProblems extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("code", switch (statusCode.value()) {
                case 400 -> "INVALID_REQUEST";
                case 404 -> "RESOURCE_NOT_FOUND";
                default -> {
                    HttpStatus status = HttpStatus.resolve(statusCode.value());
                    yield status != null ? status.name() : "ERROR";
                }
            });
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
}
