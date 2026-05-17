package uz.inha.tickets.service;

import static uz.inha.tickets.domain.Enums.BookingStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.inha.tickets.domain.*;
import uz.inha.tickets.repo.*;
import uz.inha.tickets.tickets.TicketPdfService;

@Service
public class BookingService {

    final BookingRepository bookings;
    final SeatRepository seats;
    final EventRepository events;
    final HoldService holds;
    final AuditService audit;
    final RefundRepository refunds;
    final OutboxEventRepository outbox;
    final StringRedisTemplate redis;
    final TicketPdfService pdfService;

    @Value("${app.idempotency.ttl-seconds:86400}")
    long idempotencyTtl;

    public record ConfirmResult(Booking booking, boolean replay) {}

    public BookingService(
        BookingRepository b,
        SeatRepository s,
        EventRepository e,
        HoldService h,
        AuditService a,
        RefundRepository r,
        OutboxEventRepository o,
        StringRedisTemplate redis,
        TicketPdfService pdfService
    ) {
        bookings = b;
        seats = s;
        events = e;
        holds = h;
        audit = a;
        refunds = r;
        outbox = o;
        this.redis = redis;
        this.pdfService = pdfService;
    }

    static String idemKey(UUID user, String idem) {
        return "idem:booking:" + user + ":" + idem;
    }

    void cacheIdempotency(UUID user, String idem, UUID bookingId) {
        if (idem == null || idem.isBlank()) return;
        try {
            redis.opsForValue().set(idemKey(user, idem), bookingId.toString(), idempotencyTtl, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    Booking lookupIdempotent(UUID user, String idem) {
        if (idem == null || idem.isBlank()) return null;
        try {
            String cached = redis.opsForValue().get(idemKey(user, idem));
            if (cached != null) return bookings.findById(UUID.fromString(cached)).orElse(null);
        } catch (Exception ignored) {}
        return bookings.findByUserIdAndIdempotencyKey(user, idem).orElse(null);
    }

    @Transactional
    public ConfirmResult confirm(UserAccount u, String token, String idem, String paymentToken) {
        if (idem == null || idem.isBlank()) throw DomainException.bad("Idempotency-Key header is required");
        Booking cached = lookupIdempotent(u.id, idem);
        if (cached != null) return new ConfirmResult(cached, true);
        authorizePayment(paymentToken);
        HoldService.HoldResult h = holds.verify(u.id, token);
        Event ev = events.findById(h.eventId()).orElseThrow(() -> DomainException.notFound("event not found"));
        Booking bk = new Booking(u, ev, idem);
        for (Seat s : seats.findByIdIn(h.seatIds())) {
            bk.totalCents += s.priceCents;
            bk.seats.add(new BookingSeat(bk, ev, s));
        }
        Booking saved = bookings.saveAndFlush(bk);
        holds.releaseVerified(h);
        audit.record(u.id, "CONFIRMED", "booking", saved.id, "seats=" + h.seatIds());
        String trace = MDC.get("trace_id");
        for (BookingSeat bs : saved.seats) {
            OutboxEvent ev1 = new OutboxEvent(
                "ws.broadcast",
                "Booking",
                saved.id,
                "SEAT_BOOKED",
                "{\"type\":\"BOOKED\",\"event_id\":\"" + ev.id + "\",\"seat_id\":\"" + bs.seat.id + "\",\"status\":\"BOOKED\",\"booking_id\":\"" + saved.id + "\"}",
                trace
            );
            outbox.save(ev1);
        }
        cacheIdempotency(u.id, idem, saved.id);
        return new ConfirmResult(saved, false);
    }

    void authorizePayment(String token) {
        if (token == null || token.isBlank() || "MOCK_PAY_OK".equals(token)) return;
        if ("MOCK_PAY_DECLINED".equals(token)) throw DomainException.unprocessable("payment declined");
        if ("MOCK_PAY_TIMEOUT".equals(token)) throw DomainException.gatewayTimeout("payment timeout");
        throw DomainException.bad("unsupported payment token");
    }

    public List<Booking> history(UserAccount u) {
        return bookings.findByUserIdOrderByCreatedAtDesc(u.id);
    }

    public Booking get(UserAccount u, UUID id) {
        Booking b = bookings.findById(id).orElseThrow(() -> DomainException.notFound("booking not found"));
        if (!b.user.id.equals(u.id) && !b.event.organizer.id.equals(u.id)) throw DomainException.forbidden(
            "not your booking"
        );
        return b;
    }

    @Transactional
    public Booking cancel(UserAccount u, UUID id) {
        Booking b = get(u, id);
        if (b.status != BookingStatus.CONFIRMED) throw DomainException.conflict(
            "booking already cancelled or not active"
        );
        if (b.event.startsAt.minus(Duration.ofHours(24)).isBefore(Instant.now())) throw DomainException.conflict(
            "refund/cancellation window closed"
        );
        b.status = BookingStatus.CANCELLED;
        b.cancelledAt = Instant.now();
        b.refundCents = b.totalCents;
        b.seats.clear();
        Booking saved = bookings.saveAndFlush(b);
        Refund refund = refunds.save(new Refund(saved, saved.refundCents));
        saved.refund = refund;
        audit.record(u.id, "CANCELLED", "booking", b.id, "refundCents=" + b.refundCents);
        String trace = MDC.get("trace_id");
        outbox.save(
            new OutboxEvent(
                "ws.broadcast",
                "Booking",
                b.id,
                "BOOKING_CANCELLED",
                "{\"type\":\"CANCELLED\",\"event_id\":\"" + b.event.id + "\",\"booking_id\":\"" + b.id + "\"}",
                trace
            )
        );
        return saved;
    }

    public byte[] pdf(UserAccount u, UUID id) {
        Booking b = get(u, id);
        if (b.status != BookingStatus.CONFIRMED) throw DomainException.conflict(
            "ticket unavailable for cancelled booking"
        );
        return pdfService.render(b);
    }

    @Transactional
    public int cancelAllForEvent(UserAccount actor, Event ev, String reason) {
        var all = bookings.findByEventId(ev.id);
        String trace = MDC.get("trace_id");
        int cancelled = 0;
        for (Booking b : all) {
            if (b.status != BookingStatus.CONFIRMED) continue;
            b.status = BookingStatus.CANCELLED;
            b.cancelledAt = Instant.now();
            b.refundCents = b.totalCents;
            b.seats.clear();
            Booking saved = bookings.saveAndFlush(b);
            Refund refund = refunds.save(new Refund(saved, saved.refundCents));
            saved.refund = refund;
            audit.record(actor.id, "CANCELLED_BY_EVENT_DELETION", "booking", b.id, "reason=" + reason);
            outbox.save(
                new OutboxEvent(
                    "ws.broadcast",
                    "Booking",
                    b.id,
                    "BOOKING_CANCELLED",
                    "{\"type\":\"CANCELLED\",\"event_id\":\"" + ev.id + "\",\"booking_id\":\"" + b.id + "\",\"reason\":\"EVENT_DELETED\"}",
                    trace
                )
            );
            cancelled++;
        }
        return cancelled;
    }
}
