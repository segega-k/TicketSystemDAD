package uz.inha.tickets.web;

import static uz.inha.tickets.domain.Enums.BookingStatus;
import static uz.inha.tickets.domain.Enums.Role;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uz.inha.tickets.domain.Event;
import uz.inha.tickets.domain.UserAccount;
import uz.inha.tickets.repo.BookingRepository;
import uz.inha.tickets.repo.EventRepository;
import uz.inha.tickets.service.DomainException;

@RestController
public class DashboardController {

    final Current cur;
    final EventRepository events;
    final BookingRepository bookings;

    public DashboardController(Current c, EventRepository e, BookingRepository b) {
        cur = c;
        events = e;
        bookings = b;
    }

    @GetMapping({ "/api/organizer/dashboard", "/api/v1/organizer/dashboard" })
    Map<String, Object> dash() {
        UserAccount u = cur.user();
        if (u.role != Role.ORGANIZER && u.role != Role.ADMIN) throw DomainException.forbidden(
            "organizer role required"
        );
        var ev = events.findByOrganizerId(u.id);
        var bs = bookings.findByEventOrganizerId(u.id);
        long revenue = bs.stream().filter(b -> b.status == BookingStatus.CONFIRMED).mapToLong(b -> b.totalCents).sum();
        long tickets = bs
            .stream()
            .filter(b -> b.status == BookingStatus.CONFIRMED)
            .mapToLong(b -> b.seats.size())
            .sum();
        return Map.of(
            "events",
            ev.size(),
            "confirmedBookings",
            bs.size(),
            "confirmed_bookings",
            bs.size(),
            "ticketsSold",
            tickets,
            "tickets_sold",
            tickets,
            "revenueCents",
            revenue,
            "revenue_cents",
            revenue
        );
    }

    @GetMapping(
        {
            "/api/organizer/events/{eventId}/analytics",
            "/api/v1/organizer/events/{eventId}/dashboard",
            "/api/v1/organizer/events/{eventId}/analytics",
        }
    )
    Map<String, Object> analytics(@PathVariable UUID eventId) {
        UserAccount u = cur.user();
        Event ev = events.findById(eventId).orElseThrow(() -> DomainException.notFound("event not found"));
        if (!ev.organizer.id.equals(u.id) && u.role != Role.ADMIN) throw DomainException.forbidden("not your event");
        var bs = bookings
            .findByEventOrganizerId(ev.organizer.id)
            .stream()
            .filter(b -> b.event.id.equals(eventId))
            .toList();
        long revenue = bs.stream().filter(b -> b.status == BookingStatus.CONFIRMED).mapToLong(b -> b.totalCents).sum();
        long tickets = bs
            .stream()
            .filter(b -> b.status == BookingStatus.CONFIRMED)
            .mapToLong(b -> b.seats.size())
            .sum();
        return Map.of(
            "eventId",
            eventId,
            "event_id",
            eventId,
            "bookings",
            bs.size(),
            "ticketsSold",
            tickets,
            "tickets_sold",
            tickets,
            "revenueCents",
            revenue,
            "revenue_cents",
            revenue,
            "byStatus",
            bs.stream().collect(Collectors.groupingBy(b -> b.status, Collectors.counting())),
            "by_status",
            bs.stream().collect(Collectors.groupingBy(b -> b.status, Collectors.counting()))
        );
    }
}
