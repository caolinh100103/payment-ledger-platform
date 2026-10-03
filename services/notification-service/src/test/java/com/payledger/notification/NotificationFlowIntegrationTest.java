package com.payledger.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Duration;
import java.util.UUID;

import static com.payledger.notification.TestcontainersConfiguration.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** From a transfer event on Kafka to the messages a customer can list. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationFlowIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    NotificationStore store;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestJwtIssuer tokens;

    @Test
    void completedTransferNotifiesBothCustomers() throws Exception {
        UUID eventId = UUID.randomUUID();

        kafka.send(TOPIC, "transfer-1", TestEvents.completedTransfer(eventId, "Rent October")).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> store.findByEvent(eventId).size() == 2);
        assertThat(store.findByEvent(eventId)).extracting(Notification::recipientId)
                .containsExactlyInAnyOrder("alice", "bob");
        // Bob, signed in, reads his own messages.
        assertThat(mvc.get().uri("/api/v1/notifications").header("Authorization", tokens.bearer("bob", "CUSTOMER")))
                .hasStatusOk()
                .bodyJson().extractingPath("$[0].message").asString().contains("+250,000 VND", "ND: Rent October");
    }
}
