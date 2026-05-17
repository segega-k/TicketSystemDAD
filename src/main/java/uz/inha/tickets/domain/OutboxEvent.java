package uz.inha.tickets.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    public String topic;

    @Column(length = 8000)
    public String payload;

    public Instant createdAt = Instant.now();
    public Instant publishedAt;

    protected OutboxEvent() {}

    public OutboxEvent(String t, String p) {
        topic = t;
        payload = p;
    }
}
