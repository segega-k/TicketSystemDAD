package uz.inha.tickets.repo;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import uz.inha.tickets.domain.Event;

public interface EventRepository extends JpaRepository<Event, UUID> {
    @Query(
        "select e from Event e where (:q is null or lower(e.title) like lower(concat('%',:q,'%')) or lower(e.city) like lower(concat('%',:q,'%'))) and (:cursor is null or e.startsAt > :cursor) order by e.startsAt asc"
    )
    List<Event> search(
        @Param("q") String q,
        @Param("cursor") Instant cursor,
        org.springframework.data.domain.Pageable pageable
    );

    List<Event> findByOrganizerId(UUID organizerId);
}
