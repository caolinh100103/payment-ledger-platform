package com.payledger.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Writes an RFC 9457 problem from a servlet filter, where no {@code @ExceptionHandler} applies, in the same shape
 * as the rest of the API: a stable {@code code} next to the standard fields.
 */
@Component
public class ProblemWriter {

    private final JsonMapper jsonMapper;

    ProblemWriter(JsonMapper jsonMapper) {
        // Same output as Spring MVC: problem "properties" are flattened into the top-level JSON object.
        this.jsonMapper = jsonMapper.rebuild().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
