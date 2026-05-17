package uz.inha.tickets.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.inha.tickets.domain.Booking;
import uz.inha.tickets.domain.BookingSeat;
import uz.inha.tickets.service.BookingService;

@RestController
@Tag(name = "Bookings")
public class BookingController {

    final BookingService svc;
    final Current cur;

    public BookingController(BookingService s, Current c) {
        svc = s;
        cur = c;
    }

    public record ConfirmReq(
        @NotBlank @JsonAlias({ "hold_token", "hold_group_id" }) String holdToken,
        @JsonAlias("idempotency_key") String idempotencyKey,
        @JsonAlias("payment_token") String paymentToken
    ) {}

    @PostMapping({ "/api/bookings", "/api/v1/bookings" })
    @Operation(summary = "Confirm booking from a hold")
    @ApiResponse(responseCode = "201", description = "Booking created")
    public ResponseEntity<Map<String, Object>> confirm(
        @Valid @RequestBody ConfirmReq r,
        @RequestHeader(name = "Idempotency-Key", required = false) String idemHeader,
        HttpServletRequest request
    ) {
        boolean v1 = request.getRequestURI().startsWith("/api/v1/");
        String idem = idemHeader != null && !idemHeader.isBlank() ? idemHeader : (v1 ? null : r.idempotencyKey);
        var result = svc.confirm(cur.user(), r.holdToken, idem, r.paymentToken);
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(
            dto(result.booking(), true)
        );
    }

    @GetMapping({ "/api/bookings", "/api/v1/bookings", "/api/v1/users/me/bookings" })
    @Operation(summary = "List current user's bookings")
    public Object history(
        HttpServletRequest req,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int limit
    ) {
        int pageSize = Math.min(Math.max(limit, 1), 100);
        java.time.Instant before = null;
        if (cursor != null && !cursor.isBlank()) before = java.time.Instant.parse(cursor);
        final java.time.Instant finalBefore = before;
        var page = svc
            .history(cur.user())
            .stream()
            .filter(b -> finalBefore == null || b.createdAt.isBefore(finalBefore))
            .limit(pageSize + 1L)
            .toList();
        var visible = page.stream().limit(pageSize).toList();
        var items = visible.stream().map(b -> dto(b, false)).toList();
        String next = page.size() > pageSize ? visible.getLast().createdAt.toString() : "";
        if (req.getRequestURI().equals("/api/v1/users/me/bookings")) return Map.of("items", items, "next_cursor", next);
        return items;
    }

    @GetMapping({ "/api/bookings/{id}", "/api/v1/bookings/{id}" })
    public Map<String, Object> get(@PathVariable UUID id) {
        return dto(svc.get(cur.user(), id), true);
    }

    @PostMapping({ "/api/bookings/{id}/cancel", "/api/v1/bookings/{id}/cancel" })
    public Map<String, Object> cancel(@PathVariable UUID id) {
        return dto(svc.cancel(cur.user(), id), false);
    }

    @GetMapping(
        value = { "/api/bookings/{id}/ticket.pdf", "/api/v1/bookings/{id}/ticket.pdf" },
        produces = "application/pdf"
    )
    public byte[] pdf(@PathVariable UUID id, HttpServletResponse res) {
        res.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ticket-" + id + ".pdf\"");
        return svc.pdf(cur.user(), id);
    }

    static Map<String, Object> dto(Booking b, boolean includeGroup) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", b.id);
        out.put("eventId", b.event.id);
        out.put("event_id", b.event.id);
        out.put("userId", b.user.id);
        out.put("user_id", b.user.id);
        out.put("status", b.status.name());
        out.put("booking_status", b.status.name());
        out.put("totalCents", b.totalCents);
        out.put("total_cents", b.totalCents);
        out.put("total_amount", money(b.totalCents));
        out.put("createdAt", b.createdAt);
        out.put("created_at", b.createdAt);
        out.put("cancelledAt", b.cancelledAt);
        out.put("cancelled_at", b.cancelledAt);
        out.put("refundCents", b.refundCents);
        out.put("refund_cents", b.refundCents);
        List<Map<String, Object>> seats = b.seats.stream().map(BookingController::seatDto).toList();
        out.put("seats", seats);
        if (includeGroup) {
            out.put("booking_group_id", b.id);
            out.put(
                "bookings",
                seats
                    .stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", b.id);
                        m.put("seat_id", s.get("seat_id"));
                        m.put("row", s.get("row"));
                        m.put("number", s.get("number"));
                        m.put("amount", s.get("amount"));
                        return m;
                    })
                    .toList()
            );
            out.put("ticket_pdf_urls", List.of("/api/v1/bookings/" + b.id + "/ticket.pdf"));
        }
        if (b.refund != null || b.refundCents > 0) {
            Map<String, Object> r = new LinkedHashMap<>();
            if (b.refund != null) {
                r.put("id", b.refund.id);
                r.put("refunded_at", b.refund.refundedAt);
            }
            r.put("amount", money(b.refundCents));
            r.put("amount_cents", b.refundCents);
            out.put("refund", r);
        }
        out.put("booking_id", b.id);
        return out;
    }

    static Map<String, Object> seatDto(BookingSeat bs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", bs.seat.id);
        m.put("seat_id", bs.seat.id);
        m.put("row", bs.seat.rowLabel);
        m.put("number", bs.seat.seatNumber);
        m.put("amount", money(bs.seat.priceCents));
        m.put("amount_cents", bs.seat.priceCents);
        return m;
    }

    static String money(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
