package com.payledger.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.payledger.notification.TestcontainersConfiguration.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** At-least-once delivery in, at-most-once effect out. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotentConsumerIntegrationTest {

    @Autowired
    TransferNotifier notifier;

    @Autowired
    NotificationStore store;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @MockitoSpyBean
    NotificationSender sender;

    @BeforeEach
    void resetSender() {
        clearInvocations(sender);
    }

    @Test
    void redeliveredEventIsNotNotifiedAgain() {
        TransferEvent event = completedTransfer();

        assertThat(notifier.handle(event)).hasSize(2);
        assertThat(notifier.handle(event)).isEmpty();

        assertThat(store.findByEvent(event.id())).hasSize(2);
        verify(sender, times(2)).send(argThat(n -> n.eventId().equals(event.id())));
    }

    @Test
    void concurrentDeliveriesOfTheSameEventActOnce() throws Exception {
        TransferEvent event = completedTransfer();
        int deliveries = 10;
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> sent = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(deliveries)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < deliveries; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return notifier.handle(event).size();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                sent.add(result.get());
            }
        }

        // One delivery claimed the event and sent both messages; the other nine found it already processed.
        assertThat(sent).containsOnly(0, 2).filteredOn(n -> n == 2).hasSize(1);
        assertThat(store.findByEvent(event.id())).hasSize(2);
        verify(sender, times(2)).send(argThat(n -> n.eventId().equals(event.id())));
    }

    @Test
    void failedSendRollsBackTheMarkerSoTheRetryStillNotifies() {
        TransferEvent event = completedTransfer();
        doThrow(new IllegalStateException("SMS gateway timed out")).doCallRealMethod().when(sender).send(any());

        assertThatThrownBy(() -> notifier.handle(event)).hasMessage("SMS gateway timed out");
        assertThat(store.findByEvent(event.id())).isEmpty();
        assertThat(processedCount(event.id())).isZero();

        assertThat(notifier.handle(event)).hasSize(2);
        assertThat(store.findByEvent(event.id())).hasSize(2);
        assertThat(processedCount(event.id())).isOne();
        doCallRealMethod().when(sender).send(any());
    }

    @Test
    void sameMessageDeliveredTwiceByKafkaNotifiesOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        String message = TestEvents.completedTransfer(eventId, "Rent");
        UUID markerId = UUID.randomUUID();

        kafka.send(TOPIC, "transfer-2", message).get();
        kafka.send(TOPIC, "transfer-2", message).get();
        // Same key, so same partition and processed in order: once the marker is in, both copies were handled.
        kafka.send(TOPIC, "transfer-2", TestEvents.completedTransfer(markerId, "Marker")).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> store.findByEvent(markerId).size() == 2);
        assertThat(store.findByEvent(eventId)).hasSize(2);
    }

    private TransferEvent completedTransfer() {
        return TransferEvent.parse(TestEvents.completedTransfer(UUID.randomUUID(), "Rent"), json);
    }

    private long processedCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class, eventId);
    }
}
