package uz.inha.tickets.service;

import static uz.inha.tickets.domain.Enums.BookingStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.inha.tickets.domain.*;
import uz.inha.tickets.repo.*;

@Service
public class BookingService {

    final BookingRepository bookings;
    final SeatRepository seats;
    final EventRepository events;
    final HoldService holds;
    final AuditService audit;
    final RefundRepository refunds;
    final OutboxEventRepository outbox;

    public record ConfirmResult(Booking booking, boolean replay) {}

    public BookingService(
        BookingRepository b,
        SeatRepository s,
        EventRepository e,
        HoldService h,
        AuditService a,
        RefundRepository r,
        OutboxEventRepository o
    ) {
        bookings = b;
        seats = s;
        events = e;
        holds = h;
        audit = a;
        refunds = r;
        outbox = o;
    }

    @Transactional
    public ConfirmResult confirm(UserAccount u, String token, String idem, String paymentToken) {
        if (idem == null || idem.isBlank()) throw DomainException.bad("Idempotency-Key header is required");
        var existing = bookings.findByUserIdAndIdempotencyKey(u.id, idem);
        if (existing.isPresent()) return new ConfirmResult(existing.get(), true);
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
        outbox.save(
            new OutboxEvent(
                "ws.broadcast",
                "{\"type\":\"BOOKED\",\"event_id\":\"" + ev.id + "\",\"booking_id\":\"" + saved.id + "\"}"
            )
        );
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
        outbox.save(
            new OutboxEvent(
                "ws.broadcast",
                "{\"type\":\"CANCELLED\",\"event_id\":\"" + b.event.id + "\",\"booking_id\":\"" + b.id + "\"}"
            )
        );
        return saved;
    }

    public byte[] pdf(UserAccount u, UUID id) {
        Booking b = get(u, id);
        if (b.status != BookingStatus.CONFIRMED) throw DomainException.conflict(
            "ticket unavailable for cancelled booking"
        );
        String seatText = b.seats.stream().map(bs -> bs.seat.rowLabel + bs.seat.seatNumber).toList().toString();
        String text =
            "Ticket " + b.id + " | Event: " + b.event.title + " | Seats: " + seatText + " | Status: " + b.status;
        return simplePdf(text);
    }

    byte[] simplePdf(String text) {
        String safe = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        String stream = "BT /F1 12 Tf 50 760 Td (" + safe + ") Tj ET\n";
        List<String> objects = new ArrayList<>();
        objects.add("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
        objects.add("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n");
        objects.add(
            "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >> endobj\n"
        );
        objects.add(
            "4 0 obj << /Length " +
            stream.getBytes(StandardCharsets.ISO_8859_1).length +
            " >> stream\n" +
            stream +
            "endstream endobj\n"
        );
        objects.add("5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (String obj : objects) {
            offsets.add(pdf.toString().getBytes(StandardCharsets.ISO_8859_1).length);
            pdf.append(obj);
        }
        int xref = pdf.toString().getBytes(StandardCharsets.ISO_8859_1).length;
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) pdf.append(String.format("%010d 00000 n \n", off));
        pdf
            .append("trailer << /Size ")
            .append(objects.size() + 1)
            .append(" /Root 1 0 R >>\nstartxref\n")
            .append(xref)
            .append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
