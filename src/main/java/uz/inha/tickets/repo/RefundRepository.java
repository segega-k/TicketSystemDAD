package uz.inha.tickets.repo;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.inha.tickets.domain.*;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByBookingId(UUID bookingId);
    List<Refund> findByBookingEventId(UUID eventId);
}
