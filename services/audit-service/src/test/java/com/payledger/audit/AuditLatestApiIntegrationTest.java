package com.payledger.audit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The auditor's feed: the latest events, newest first, optionally those of one actor, paged by {@code seq}. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditLatestApiIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestJwtIssuer tokens;

    @Autowired
    AuditLog auditLog;

    @Test
    void pagesThroughOneActorsEventsNewestFirst() {
        String actor = "user:" + UUID.randomUUID();
        List<String> resources = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            resources.add(record(actor));
            record("user:" + UUID.randomUUID());
        }

        List<String> seen = new ArrayList<>();
        Long beforeSeq = null;
        boolean hasMore = true;
        while (hasMore) {
            MvcTestResult page = latest(actor, beforeSeq, 2);
            assertThat(page).hasStatusOk();
            List<String> pageResources = JsonPath.read(body(page), "$.data[*].resourceId");
            List<Number> seqs = JsonPath.read(body(page), "$.data[*].seq");
            seen.addAll(pageResources);
            hasMore = JsonPath.read(body(page), "$.hasMore");
            beforeSeq = seqs.getLast().longValue();
        }

        assertThat(seen).containsExactlyElementsOf(resources.reversed());
    }

    @Test
    void withoutAnActorShowsEveryonesLatest() {
        String newest = record("user:" + UUID.randomUUID());

        MvcTestResult result = latest(null, null, 1);

        assertThat(result).bodyJson().extractingPath("$.data[0].resourceId").isEqualTo(newest);
        assertThat(result).bodyJson().extractingPath("$.data[0].event.type")
                .isEqualTo("com.payledger.transfer.completed");
        assertThat(result).bodyJson().extractingPath("$.hasMore").isEqualTo(true);
    }

    @Test
    void anOutOfRangeLimitIsAnInvalidRequest() {
        MvcTestResult result = latest(null, null, 1_000);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(result).bodyJson().extractingPath("$.invalidParams[0].name").isEqualTo("limit");
    }

    @Test
    void onlyAuditorsReadIt() {
        assertThat(mvc.get().uri("/api/v1/audit-events/latest")
                .header("Authorization", tokens.bearer("operator-1", "OPERATOR")))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    private String record(String actor) {
        String resource = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        auditLog.append(new AuditableEvent(id, actor, "com.payledger.transfer.completed", resource,
                Instant.now().truncatedTo(ChronoUnit.MICROS), "/payledger/core",
                AuditTestEvents.cloudEvent(id, "com.payledger.transfer.completed", resource, actor)));
        return resource;
    }

    private MvcTestResult latest(String actor, Long beforeSeq, int limit) {
        var request = mvc.get().uri("/api/v1/audit-events/latest")
                .header("Authorization", tokens.bearer("auditor-1", "AUDITOR"))
                .param("limit", Integer.toString(limit));
        if (actor != null) {
            request = request.param("actor", actor);
        }
        if (beforeSeq != null) {
            request = request.param("beforeSeq", beforeSeq.toString());
        }
        return request.exchange();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getMvcResult().getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
