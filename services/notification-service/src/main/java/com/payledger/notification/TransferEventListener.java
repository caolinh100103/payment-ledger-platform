package com.payledger.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class TransferEventListener {

    private final TransferNotifier notifier;
    private final JsonMapper json;

    TransferEventListener(TransferNotifier notifier, JsonMapper json) {
        this.notifier = notifier;
        this.json = json;
    }

    @KafkaListener(id = "notifications", topics = "${payledger.notification.topic}")
    void onEvent(ConsumerRecord<String, String> record) {
        notifier.handle(TransferEvent.parse(record.value(), json));
    }
}
