package uz.inha.tickets.repo;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.inha.tickets.domain.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {}
