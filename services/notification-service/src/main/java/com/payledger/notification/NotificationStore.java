package com.payledger.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class NotificationStore {

    private static final RowMapper<Notification> ROW = (rs, row) -> new Notification(
            rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class), rs.getString("recipient_id"),
            Notification.Channel.valueOf(rs.getString("channel")), rs.getString("template"), rs.getString("message"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    NotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void save(Notification n) {
        jdbc.update("""
                INSERT INTO notifications (id, event_id, recipient_id, channel, template, message, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, n.id(), n.eventId(), n.recipientId(), n.channel().name(), n.template(), n.message(),
                n.createdAt().atOffset(ZoneOffset.UTC));
    }

    List<Notification> findByRecipient(String recipientId, int limit) {
        return jdbc.query("SELECT * FROM notifications WHERE recipient_id = ? ORDER BY created_at DESC, id LIMIT ?",
                ROW, recipientId, limit);
    }

    List<Notification> findByEvent(UUID eventId) {
        return jdbc.query("SELECT * FROM notifications WHERE event_id = ? ORDER BY template", ROW, eventId);
    }
}
