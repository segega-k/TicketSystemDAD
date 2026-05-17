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

    @Column(name = "trace_id", length = 64)
    public String traceId;

    @Column(nullable = false)
    public int attempts = 0;

    @Column(name = "aggregate_type", length = 40)
    public String aggregateType;

    @Column(name = "aggregate_id")
    public UUID aggregateId;

    @Column(name = "event_type", length = 40)
    public String eventType;

    protected OutboxEvent() {}

    public OutboxEvent(String t, String p) {
        topic = t;
        payload = p;
    }

    public OutboxEvent(String topic, String aggregateType, UUID aggregateId, String eventType, String payload, String traceId) {
        this.topic = topic;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.traceId = traceId;
    }
}
