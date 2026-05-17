package uz.inha.tickets.web;

import static uz.inha.tickets.domain.Enums.Role;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.inha.tickets.domain.Event;
import uz.inha.tickets.domain.Seat;
import uz.inha.tickets.domain.UserAccount;
import uz.inha.tickets.repo.BookingSeatRepository;
import uz.inha.tickets.repo.EventRepository;
import uz.inha.tickets.repo.SeatRepository;
import uz.inha.tickets.service.AuditService;
import uz.inha.tickets.service.DomainException;
import uz.inha.tickets.service.HoldService;

@RestController
@RequestMapping({ "/api/events", "/api/v1/events" })
@Tag(name = "Events")
public class EventController {

    final EventRepository events;
    final SeatRepository seats;
    final BookingSeatRepository booked;
    final HoldService holds;
    final Current cur;
    final AuditService audit;

    public EventController(
        EventRepository e,
        SeatRepository s,
        BookingSeatRepository b,
        HoldService h,
        Current c,
        AuditService a
    ) {
        events = e;
        seats = s;
        booked = b;
        holds = h;
        cur = c;
        audit = a;
    }

    public record RowConfig(
        @NotBlank String label,
        @JsonAlias("seat_count") int seatCount,
        String tier,
        String price,
        @JsonAlias("price_cents") Long priceCents
    ) {}

    public record Create(
        @NotBlank @JsonAlias("name") String title,
        String description,
        @NotNull @JsonAlias({ "starts_at", "event_date" }) Instant startsAt,
        @JsonAlias("venue_name") String venueName,
        String city,
        Object rows,
        @JsonAlias("row_count") Integer rowCount,
        @JsonAlias("seats_per_row") Integer seatsPerRow,
        @JsonAlias("price_cents") Long priceCents
    ) {}

    @GetMapping
    @Operation(summary = "List events")
    public Map<String, Object> list(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) Instant cursor,
        @RequestParam(defaultValue = "20") int limit
    ) {
        String query = q == null ? "" : q;
        Instant after = cursor == null ? Instant.EPOCH : cursor;
        var list = events.search(query, after, PageRequest.of(0, Math.min(limit, 100)));
        Instant next = list.size() == limit ? list.getLast().startsAt : null;
        return Map.of(
            "items",
            list.stream().map(ev -> eventDto(ev, false)).toList(),
            "nextCursor",
            next == null ? "" : next.toString(),
            "next_cursor",
            next == null ? "" : next.toString()
        );
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get event detail")
    Map<String, Object> detail(@PathVariable UUID id) {
        Event ev = events.findById(id).orElseThrow(() -> DomainException.notFound("event not found"));
        return eventDto(ev, true);
    }

    /**
     * Sürücü-нейтральный DTO события: фронтовый адаптер ждёт name/event_date/total_seats и
     * диапазон цен, а JPA-сущность сериализуется как title/startsAt без агрегатов мест.
     * Отдаём оба варианта именования (snake + camel) по той же схеме, что и create()/seatMap().
     */
    private Map<String, Object> eventDto(Event ev, boolean detail) {
        var evSeats = seats.findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(ev.id);
        int total = evSeats.size();
        long min = evSeats.stream().mapToLong(s -> s.priceCents).min().orElse(0);
        long max = evSeats.stream().mapToLong(s -> s.priceCents).max().orElse(0);
        var bookedIds = booked.bookedSeatIds(ev.id);
        int bookedCount = bookedIds.size();
        int available = Math.max(0, total - bookedCount);
        String date = ev.startsAt == null ? "" : ev.startsAt.toString();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ev.id);
        m.put("name", ev.title);
        m.put("title", ev.title);
        m.put("event_date", date);
        m.put("eventDate", date);
        m.put("starts_at", date);
        m.put("startsAt", date);
        m.put("venue_name", ev.venueName);
        m.put("venueName", ev.venueName);
        m.put("city", ev.city);
        m.put("status", ev.status == null ? "PUBLISHED" : ev.status.name());
        m.put("total_seats", total);
        m.put("totalSeats", total);
        m.put("min_price", cents(min));
        m.put("minPrice", cents(min));
        m.put("max_price", cents(max));
        m.put("maxPrice", cents(max));
        if (detail) {
            m.put("description", ev.description);
            Map<String, Object> org = new LinkedHashMap<>();
            org.put("id", ev.organizer.id);
            org.put("full_name", ev.organizer.displayName);
            org.put("fullName", ev.organizer.displayName);
            m.put("organizer", org);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", total);
            summary.put("available", available);
            summary.put("booked", bookedCount);
            m.put("seats_summary", summary);
            m.put("seatsSummary", summary);
        }
        return m;
    }

    @PostMapping
    @Operation(summary = "Create event and generated seat map")
    @ApiResponse(responseCode = "201", description = "Created for /api/v1")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody Create r, HttpServletRequest req) {
        UserAccount u = cur.user();
        if (u.role != Role.ORGANIZER && u.role != Role.ADMIN) throw DomainException.forbidden(
            "organizer role required"
        );
        Event ev = events.save(new Event(u, r.title, r.description, r.startsAt, r.venueName, r.city));
        int total = 0;
        if (r.rows instanceof List<?> rawRows && !rawRows.isEmpty()) {
            for (RowConfig row : rowConfigs(rawRows)) {
                int count = Math.max(1, row.seatCount());
                long cents = row.priceCents() != null ? row.priceCents() : priceToCents(row.price());
                String tier = row.tier() == null || row.tier().isBlank() ? "MAIN" : row.tier();
                for (int n = 1; n <= count; n++) seats.save(new Seat(ev, tier, row.label(), n, cents));
                total += count;
            }
        } else {
            int legacyRows = r.rows instanceof Number n ? n.intValue() : (r.rowCount() == null ? 0 : r.rowCount());
            int rows = Math.max(1, legacyRows == 0 ? 5 : legacyRows);
            int per = Math.max(1, r.seatsPerRow() == null || r.seatsPerRow() == 0 ? 10 : r.seatsPerRow());
            long cents = r.priceCents() == null || r.priceCents() == 0 ? 5000 : r.priceCents();
            for (int i = 0; i < rows; i++) {
                String row = "" + (char) ('A' + i);
                for (int n = 1; n <= per; n++) seats.save(new Seat(ev, "MAIN", row, n, cents));
            }
            total = rows * per;
        }
        audit.record(u.id, "CREATED", "event", ev.id, "seats=" + total);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ev.id);
        out.put("total_seats", total);
        out.put("totalSeats", total);
        out.put("name", ev.title);
        out.put("title", ev.title);
        HttpStatus status = req.getRequestURI().startsWith("/api/v1/") ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(out);
    }

    @GetMapping({ "/{id}/seat-map", "/{id}/seats" })
    @Operation(summary = "Get SPEC-shaped grouped seat map")
    Map<String, Object> seatMap(@PathVariable UUID id) {
        if (!events.existsById(id)) throw DomainException.notFound("event not found");
        var bookedIds = booked.bookedSeatIds(id);
        var heldIds = holds.activeHeldSeatIds(id);
        var allSeats = seats
            .findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(id)
            .stream()
            .sorted(java.util.Comparator.comparing((Seat s) -> s.rowLabel).thenComparingInt(s -> s.seatNumber))
            .toList();
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        List<Map<String, Object>> legacySeats = new ArrayList<>();
        for (Seat s : allSeats) {
            String status = bookedIds.contains(s.id) ? "BOOKED" : (heldIds.contains(s.id) ? "HELD" : "AVAILABLE");
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", s.id);
            dto.put("number", s.seatNumber);
            dto.put("seatNumber", s.seatNumber);
            dto.put("row", s.rowLabel);
            dto.put("tier", s.section);
            dto.put("section", s.section);
            dto.put("price", cents(s.priceCents));
            dto.put("price_cents", s.priceCents);
            dto.put("priceCents", s.priceCents);
            dto.put("status", status);
            grouped.computeIfAbsent(s.rowLabel, k -> new ArrayList<>()).add(dto);
            legacySeats.add(dto);
        }
        List<Map<String, Object>> rows = grouped
            .entrySet()
            .stream()
            .map(e -> Map.<String, Object>of("label", e.getKey(), "seats", e.getValue()))
            .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event_id", id);
        out.put("eventId", id);
        out.put("rows", rows);
        out.put("fetched_at", Instant.now());
        out.put("seats", legacySeats);
        out.put("bookedSeatIds", bookedIds);
        out.put("booked_seat_ids", bookedIds);
        out.put("heldSeatIds", heldIds);
        out.put("held_seat_ids", heldIds);
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<RowConfig> rowConfigs(List<?> raw) {
        return raw
            .stream()
            .map(x -> {
                Map<String, Object> m = (Map<String, Object>) x;
                Object priceCents = m.getOrDefault("price_cents", m.get("priceCents"));
                Object seatCount = m.getOrDefault("seat_count", m.getOrDefault("seatCount", 0));
                return new RowConfig(
                    String.valueOf(m.get("label")),
                    ((Number) seatCount).intValue(),
                    (String) m.get("tier"),
                    m.get("price") == null ? null : String.valueOf(m.get("price")),
                    priceCents instanceof Number n ? n.longValue() : null
                );
            })
            .collect(Collectors.toList());
    }

    static long priceToCents(String price) {
        if (price == null || price.isBlank()) return 5000;
        return new BigDecimal(price).movePointRight(2).longValueExact();
    }

    static String cents(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
