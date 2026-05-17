package uz.inha.tickets.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.inha.tickets.domain.*;
import uz.inha.tickets.repo.*;

@Service
public class AuditService {

    final AuditEventRepository audit;
    final OutboxEventRepository outbox;

    public AuditService(AuditEventRepository a, OutboxEventRepository o) {
        audit = a;
        outbox = o;
    }

    public void record(UUID actor, String action, String type, UUID id, String meta) {
        audit.save(new AuditEvent(actor, action, type, id, meta));
        outbox.save(new OutboxEvent(type + "." + action, "{\"id\":\"" + id + "\",\"actor\":\"" + actor + "\"}"));
    }
}
