package uz.inha.tickets.repo;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.inha.tickets.domain.*;

public interface BookingRepository extends JpaRepository<Booking, UUID> {
    Optional<Booking> findByUserIdAndIdempotencyKey(UUID userId, String key);
    List<Booking> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<Booking> findByEventOrganizerId(UUID organizerId);
    List<Booking> findByEventId(UUID eventId);
}
