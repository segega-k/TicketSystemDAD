package uz.inha.tickets.web;

import static uz.inha.tickets.domain.Enums.BookingStatus;
import static uz.inha.tickets.domain.Enums.Role;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uz.inha.tickets.domain.Event;
import uz.inha.tickets.domain.Seat;
import uz.inha.tickets.domain.UserAccount;
import uz.inha.tickets.repo.BookingRepository;
import uz.inha.tickets.repo.BookingSeatRepository;
import uz.inha.tickets.repo.EventRepository;
import uz.inha.tickets.repo.SeatRepository;
import uz.inha.tickets.service.DomainException;

@RestController
public class DashboardController {

    final Current cur;
    final EventRepository events;
    final BookingRepository bookings;
    final SeatRepository seats;
    final BookingSeatRepository bookedSeats;

    public DashboardController(
        Current c,
        EventRepository e,
        BookingRepository b,
        SeatRepository s,
        BookingSeatRepository bs
    ) {
        cur = c;
        events = e;
        bookings = b;
        seats = s;
        bookedSeats = bs;
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
        long refunded = bs.stream().mapToLong(b -> b.refundCents).sum();
        long tickets = bs
            .stream()
            .filter(b -> b.status == BookingStatus.CONFIRMED)
            .mapToLong(b -> b.seats.size())
            .sum();
        long cancelledTickets = bs
            .stream()
            .filter(b -> b.status != BookingStatus.CONFIRMED)
            .mapToLong(b -> b.seats.size())
            .sum();

        List<Seat> evSeats = seats.findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(eventId);
        int totalSeats = evSeats.size();
        var bookedIds = bookedSeats.bookedSeatIds(eventId);
        int available = Math.max(0, totalSeats - (int) tickets);
        double occupancy = totalSeats > 0 ? (tickets * 100.0) / totalSeats : 0.0;

        // Продажи по тарифам (section = tier): всего мест, продано, выручка по проданным.
        Map<String, long[]> tierAgg = new LinkedHashMap<>();
        for (Seat s : evSeats) {
            long[] agg = tierAgg.computeIfAbsent(s.section, k -> new long[3]);
            agg[0]++; // total
            if (bookedIds.contains(s.id)) {
                agg[1]++; // sold
                agg[2] += s.priceCents; // revenue cents
            }
        }
        List<Map<String, Object>> byTier = new ArrayList<>();
        for (var e : tierAgg.entrySet()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tier", e.getKey());
            t.put("total", e.getValue()[0]);
            t.put("sold", e.getValue()[1]);
            t.put("revenueCents", e.getValue()[2]);
            t.put("revenue", money(e.getValue()[2]));
            byTier.add(t);
        }

        // Дневные продажи за последние 30 дней (UTC), по подтверждённым бронированиям.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(29);
        Map<LocalDate, long[]> daily = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) daily.put(d, new long[2]);
        for (var b : bs) {
            if (b.status != BookingStatus.CONFIRMED || b.createdAt == null) continue;
            LocalDate d = b.createdAt.atZone(ZoneOffset.UTC).toLocalDate();
            long[] agg = daily.get(d);
            if (agg == null) continue;
            agg[0] += b.seats.size();
            agg[1] += b.totalCents;
        }
        List<Map<String, Object>> dailySales = new ArrayList<>();
        for (var e : daily.entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date", e.getKey().toString());
            d.put("tickets_sold", e.getValue()[0]);
            d.put("ticketsSold", e.getValue()[0]);
            d.put("revenueCents", e.getValue()[1]);
            d.put("revenue", money(e.getValue()[1]));
            dailySales.add(d);
        }

        Map<String, Object> revenueObj = new LinkedHashMap<>();
        revenueObj.put("gross", money(revenue));
        revenueObj.put("refunded", money(refunded));
        revenueObj.put("net", money(revenue - refunded));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", eventId);
        out.put("event_id", eventId);
        out.put("event_name", ev.title);
        out.put("eventName", ev.title);
        out.put("event_date", ev.startsAt == null ? "" : ev.startsAt.toString());
        out.put("eventDate", ev.startsAt == null ? "" : ev.startsAt.toString());
        out.put("bookings", bs.size());
        out.put("total_seats", totalSeats);
        out.put("totalSeats", totalSeats);
        out.put("sold", tickets);
        out.put("ticketsSold", tickets);
        out.put("tickets_sold", tickets);
        out.put("cancelled", cancelledTickets);
        out.put("available", available);
        out.put("occupancy_pct", occupancy);
        out.put("occupancyPct", occupancy);
        out.put("revenueCents", revenue);
        out.put("revenue_cents", revenue);
        out.put("refundedCents", refunded);
        out.put("revenue", revenueObj);
        out.put("by_tier", byTier);
        out.put("byTier", byTier);
        out.put("daily_sales_last_30d", dailySales);
        out.put("dailySalesLast30d", dailySales);
        out.put(
            "byStatus",
            bs.stream().collect(Collectors.groupingBy(b -> b.status, Collectors.counting()))
        );
        out.put(
            "by_status",
            bs.stream().collect(Collectors.groupingBy(b -> b.status, Collectors.counting()))
        );
        return out;
    }

    static String money(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
