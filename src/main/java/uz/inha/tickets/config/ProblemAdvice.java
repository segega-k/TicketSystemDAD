package uz.inha.tickets.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uz.inha.tickets.service.DomainException;

@RestControllerAdvice
public class ProblemAdvice {

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException e, HttpServletRequest r) {
        ProblemDetail p = problem(e.status, e.getMessage(), r);
        e.properties.forEach(p::setProperty);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status);
        Object retry = e.properties.get("retry_after_seconds");
        if (retry != null) b.header(HttpHeaders.RETRY_AFTER, String.valueOf(retry));
        return b.body(p);
    }

    @ExceptionHandler(
        {
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class,
        }
    )
    ResponseEntity<ProblemDetail> val(Exception e, HttpServletRequest r) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation failed", r);
        p.setProperty("invalid_params", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(p);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(Exception e, HttpServletRequest r) {
        DomainException de = DomainException.conflict("one or more seats already booked");
        return domain(de, r);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> illegal(Exception e, HttpServletRequest r) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            problem(HttpStatus.BAD_REQUEST, e.getMessage() == null ? "bad request" : e.getMessage(), r)
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> any(Exception e, HttpServletRequest r) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error", r)
        );
    }

    ProblemDetail problem(HttpStatus s, String msg, HttpServletRequest r) {
        String code = suffix(s, msg);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(s, msg);
        p.setType(URI.create("https://tickets.inha.uz/problems/" + code));
        p.setTitle(title(s, msg));
        p.setProperty("code", code);
        String trace = MDC.get("trace_id");
        p.setProperty("trace_id", trace != null ? trace : UUID.randomUUID().toString());
        p.setProperty("path", r.getRequestURI());
        if (s == HttpStatus.TOO_MANY_REQUESTS) p.setProperty("retry_after_seconds", 60);
        return p;
    }

    private String suffix(HttpStatus s, String msg) {
        String m = msg == null ? "" : msg.toLowerCase();
        if (m.contains("redis")) return "redis-unavailable";
        if (m.contains("validation")) return "validation-failed";
        if (m.contains("idempotency-key")) return "idempotency-key-required";
        if (m.contains("invalid credentials")) return "invalid-credentials";
        if (m.contains("invalid refresh")) return "invalid-refresh-token";
        if (m.contains("not authenticated")) return "unauthenticated";
        if (m.contains("organizer role")) return "insufficient-role";
        if (m.contains("role escalation")) return "role-escalation-forbidden";
        if (
            m.contains("belongs to another user") || m.contains("not your booking") || m.contains("not your event")
        ) return "not-owner";
        if (m.contains("event not found")) return "event-not-found";
        if (m.contains("seat not found")) return "seat-not-found";
        if (m.contains("booking not found")) return "booking-not-found";
        if (
            m.contains("hold not found") || m.contains("expired") || m.contains("token does not match")
        ) return "hold-not-found";
        if (m.contains("already held")) return "seat-already-held";
        if (m.contains("already booked") || m.contains("resource conflict")) return "seat-already-booked";
        if (m.contains("already cancelled") || m.contains("not active")) return "booking-already-cancelled";
        if (
            m.contains("cancellation") ||
            m.contains("refund") ||
            m.contains("cancelled pdf") ||
            m.contains("ticket unavailable")
        ) return "cancellation-not-allowed";
        if (m.contains("cap exceeded")) return "seat-cap-exceeded";
        if (
            m.contains("not in event") || m.contains("same section") || m.contains("same section and row")
        ) return "seats-not-same-event";
        if (m.contains("adjacent")) return "seats-not-adjacent";
        if (m.contains("rate limit")) return "rate-limited";
        if (m.contains("payment declined")) return "payment-declined";
        if (m.contains("payment timeout")) return "payment-timeout";
        if (s.is5xxServerError()) return "internal";
        return String.valueOf(s.value());
    }

    private String title(HttpStatus s, String msg) {
        return switch (suffix(s, msg)) {
            case "redis-unavailable" -> "Redis unavailable";
            case "validation-failed" -> "Validation failed";
            case "idempotency-key-required" -> "Idempotency-Key required";
            case "invalid-credentials" -> "Invalid credentials";
            case "invalid-refresh-token" -> "Invalid refresh token";
            case "unauthenticated" -> "Authentication required";
            case "insufficient-role" -> "Insufficient role";
            case "role-escalation-forbidden" -> "Role escalation forbidden";
            case "not-owner" -> "Not the resource owner";
            case "event-not-found" -> "Event not found";
            case "seat-not-found" -> "Seat not found";
            case "booking-not-found" -> "Booking not found";
            case "hold-not-found" -> "Hold not found";
            case "seat-already-held" -> "Seat already held";
            case "seat-already-booked" -> "Seat already booked";
            case "booking-already-cancelled" -> "Booking already cancelled";
            case "cancellation-not-allowed" -> "Cancellation not allowed";
            case "seat-cap-exceeded" -> "Per-event seat cap exceeded";
            case "seats-not-same-event" -> "Seats not in the same event";
            case "seats-not-adjacent" -> "Seats not adjacent";
            case "rate-limited" -> "Too many requests";
            case "payment-declined" -> "Payment declined";
            case "payment-timeout" -> "Payment timeout";
            case "internal" -> "Internal server error";
            default -> s.getReasonPhrase();
        };
    }
}
