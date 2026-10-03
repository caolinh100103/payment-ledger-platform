package com.payledger.common.observability;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logs are JSON lines in Elastic Common Schema: one line per money movement, findable by trace id and by the caller's
 * interaction id, with ids and outcome but no amounts or descriptions.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIntegrationTest extends ApiTestSupport {

    @Autowired
    JsonMapper json;

    @Test
    void logsEachMoneyMovementAsAnEcsLineInTheTraceOfItsRequest(CapturedOutput output) {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String interactionId = UUID.randomUUID().toString();

        MvcTestResult result = mvc.post().uri("/api/v1/transfers")
                .with(asOwnerOf(alice))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .header("x-fapi-interaction-id", interactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(alice, bob, 250_000, "VND"))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String transferId = jsonPath(result, "$.id");

        List<String> lines = output.getAll().lines().filter(line -> line.contains(transferId)).toList();
        assertThat(lines).hasSize(1);
        JsonNode line = json.readTree(lines.getFirst());
        assertThat(line.path("@timestamp").asString()).isNotBlank();
        assertThat(line.path("log").path("level").asString()).isEqualTo("INFO");
        assertThat(line.path("service").path("name").asString()).isEqualTo("payledger-core");
        assertThat(line.path("message").asString()).isEqualTo("TRANSFER " + transferId + " COMPLETED");
        assertThat(line.path("trace.id").asString()).isEqualTo(traceId);
        assertThat(line.path("span.id").asString()).matches("[0-9a-f]{16}");
        assertThat(line.path("fapi").path("interaction_id").asString()).isEqualTo(interactionId);
        assertThat(line.path("transfer").path("type").asString()).isEqualTo("TRANSFER");
        assertThat(line.path("transfer").path("status").asString()).isEqualTo("COMPLETED");
        assertThat(line.path("event").path("duration").asLong()).isPositive();
        // Amounts and descriptions stay in the ledger and the audit trail.
        assertThat(lines.getFirst()).doesNotContain("250000", "Tiền nhà");
    }
}
