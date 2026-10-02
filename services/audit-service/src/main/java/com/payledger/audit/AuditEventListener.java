package com.payledger.audit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records every event published on the domain topics. Kafka delivers at least once, so a redelivered event is
 * recognised by its id and not recorded twice. The offset is committed only after the row is committed.
 */
@Component
class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final AuditLog auditLog;
    private final JsonMapper json;

    AuditEventListener(AuditLog auditLog, JsonMapper json) {
        this.auditLog = auditLog;
        this.json = json;
    }

    @KafkaListener(id = "audit", topics = "${payledger.audit.topic}")
    void onEvent(ConsumerRecord<String, String> record) {
        AuditableEvent event = AuditableEvent.parse(record.value(), json);
        if (auditLog.append(event)) {
            log.debug("Recorded {} {} for {}", event.action(), event.eventId(), event.resourceId());
        } else {
            log.info("Skipped redelivered event {} ({}-{}@{})", event.eventId(), record.topic(), record.partition(),
                    record.offset());
        }
    }
}
