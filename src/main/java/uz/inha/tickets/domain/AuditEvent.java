package uz.inha.tickets.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    public UUID actorId;
    public String action;
    public String entityType;
    public UUID entityId;

    @Column(length = 4000)
    public String metadata;

    public Instant createdAt = Instant.now();

    protected AuditEvent() {}

    public AuditEvent(UUID a, String ac, String et, UUID eid, String m) {
        actorId = a;
        action = ac;
        entityType = et;
        entityId = eid;
        metadata = m;
    }
}
