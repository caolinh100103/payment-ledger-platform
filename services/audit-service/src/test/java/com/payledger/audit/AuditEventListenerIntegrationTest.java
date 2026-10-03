package com.payledger.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.payledger.audit.AuditTestEvents.cloudEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** From the Kafka topic to the audit API, as the core service's events would arrive. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditEventListenerIntegrationTest {

    private static final String TOPIC = "payledger.transfers";

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    AuditLog auditLog;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestJwtIssuer tokens;

    @Autowired
    MeterRegistry meters;

    /** Who froze an account, and who failed to sign in, go into the same chain as the money movements. */
    @Test
    void recordsAccountAndSecurityEventsInTheSameChain() throws Exception {
        String account = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();

        kafka.send("payledger.accounts", account, AuditTestEvents.cloudEvent(UUID.randomUUID(),
                "com.payledger.account.frozen", account, "user:operator-1")).get();
        kafka.send("payledger.security", user, AuditTestEvents.cloudEvent(UUID.randomUUID(),
                "com.payledger.user.sign_in_failed", user, "anonymous")).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> auditLog.findByResource(account, 10).size() == 1
                && auditLog.findByResource(user, 10).size() == 1);
        assertThat(auditLog.findByResource(account, 10).getFirst().event().actor()).isEqualTo("user:operator-1");
        assertThat(auditLog.findByResource(user, 10).getFirst().event().action())
                .isEqualTo("com.payledger.user.sign_in_failed");
        assertThat(auditLog.verify().valid()).isTrue();
    }

    @Test
    void recordsEachEventOfATransfer() throws Exception {
        String transfer = UUID.randomUUID().toString();
        List<String> types = List.of("com.payledger.transfer.created", "com.payledger.transfer.completed");
        for (String type : types) {
            kafka.send(TOPIC, transfer, cloudEvent(UUID.randomUUID(), type, transfer)).get();
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> auditLog.findByResource(transfer, 10).size() == 2);

        List<AuditRecord> records = auditLog.findByResource(transfer, 10);
        assertThat(records).extracting(r -> r.event().action()).containsExactlyElementsOf(types);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.event().actor()).isEqualTo("anonymous");
            assertThat(r.event().source()).isEqualTo("/payledger/core");
        });
        assertThat(mvc.get().uri("/api/v1/audit-events?resourceId={id}", transfer)
                .header("Authorization", tokens.bearer("auditor-1", "AUDITOR")))
                .hasStatusOk()
                .bodyJson().extractingPath("$[1].event.data.amount").isEqualTo(250000);
    }

    @Test
    void redeliveredEventIsRecordedOnce() throws Exception {
        String transfer = UUID.randomUUID().toString();
        String message = cloudEvent(UUID.randomUUID(), "com.payledger.transfer.completed", transfer);
        String marker = cloudEvent(UUID.randomUUID(), "com.payledger.transfer.reversed", transfer);
        double duplicates = consumed("duplicate");

        kafka.send(TOPIC, transfer, message).get();
        kafka.send(TOPIC, transfer, message).get();
        // Same key, so same partition: once the marker is recorded, both copies have been processed.
        kafka.send(TOPIC, transfer, marker).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> auditLog.findByResource(transfer, 10).size() == 2);
        assertThat(auditLog.findByResource(transfer, 10)).extracting(r -> r.event().action())
                .containsExactly("com.payledger.transfer.completed", "com.payledger.transfer.reversed");
        assertThat(consumed("duplicate")).isEqualTo(duplicates + 1);
    }

    @Test
    void deadLetterCountersStartAtZeroSoTheFirstParkedEventAlerts() {
        for (String topic : List.of("payledger.transfers", "payledger.accounts", "payledger.security")) {
            assertThat(meters.find("payledger.events.dead.lettered").tag("topic", topic).counter()).isNotNull();
        }
    }

    private double consumed(String outcome) {
        Counter counter = meters.find("payledger.events.consumed").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void verificationEndpointReportsTheChainHead() {
        auditLog.append(AuditTestEvents.event(UUID.randomUUID().toString()));

        assertThat(mvc.get().uri("/api/v1/audit-events/verification")
                .header("Authorization", tokens.bearer("auditor-1", "AUDITOR")))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.valid").isEqualTo(true);
                    assertThat(json).extractingPath("$.headHash").asString().hasSize(64);
                });
    }
}
