package uz.inha.tickets.repo;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.inha.tickets.domain.*;

public interface SeatRepository extends JpaRepository<Seat, UUID> {
    List<Seat> findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(UUID eventId);
    List<Seat> findByIdIn(Collection<UUID> ids);
}
