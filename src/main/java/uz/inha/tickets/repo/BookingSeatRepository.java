package uz.inha.tickets.repo;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import uz.inha.tickets.domain.BookingSeat;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, UUID> {
    @Query(
        "select bs.seat.id from BookingSeat bs where bs.event.id=:eventId and bs.booking.status in (uz.inha.tickets.domain.Enums$BookingStatus.CONFIRMED)"
    )
    Set<UUID> bookedSeatIds(@Param("eventId") UUID eventId);

    long countByBookingUserIdAndEventId(UUID userId, UUID eventId);
}
