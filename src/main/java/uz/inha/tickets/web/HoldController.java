package uz.inha.tickets.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.inha.tickets.domain.Seat;
import uz.inha.tickets.repo.SeatRepository;
import uz.inha.tickets.service.HoldService;

@RestController
@Tag(name = "Seat Holds")
public class HoldController {

    final HoldService holds;
    final Current cur;
    final SeatRepository seats;

    public HoldController(HoldService h, Current c, SeatRepository s) {
        holds = h;
        cur = c;
        seats = s;
    }

    public record HoldReq(
        @NotNull @JsonAlias("event_id") UUID eventId,
        @NotNull @Size(min = 1, max = 6) @JsonAlias("seat_ids") List<UUID> seatIds
    ) {}

    public record ReleaseReq(@NotBlank @JsonAlias({ "hold_token", "hold_group_id" }) String holdToken) {}

    @PostMapping({ "/api/holds", "/api/v1/seats/hold" })
    @Operation(summary = "Acquire a 10-minute hold on adjacent seats")
    public Map<String, Object> hold(@Valid @RequestBody HoldReq r) {
        return dto(holds.hold(cur.user().id, r.eventId, r.seatIds), seats);
    }

    @DeleteMapping({ "/api/holds", "/api/v1/seats/hold" })
    @Operation(summary = "Legacy release endpoint")
    public Map<String, String> releaseDelete(@Valid @RequestBody ReleaseReq r) {
        holds.release(cur.user().id, r.holdToken);
        return Map.of("status", "released");
    }

    @DeleteMapping("/api/v1/seats/hold/{holdGroupId}")
    @ApiResponse(responseCode = "204", description = "Hold released")
    public ResponseEntity<Void> releasePath(@PathVariable String holdGroupId) {
        holds.release(cur.user().id, holdGroupId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping({ "/api/v1/seats/release", "/api/v1/seats/hold/release" })
    public Map<String, String> releasePost(@Valid @RequestBody ReleaseReq r) {
        holds.release(cur.user().id, r.holdToken);
        return Map.of("status", "released");
    }

    static Map<String, Object> dto(HoldService.HoldResult h, SeatRepository repo) {
        Map<String, Object> out = new LinkedHashMap<>();
        Instant expires = Instant.now().plusSeconds(h.expiresInSeconds());
        List<Seat> held = repo.findByIdIn(h.seatIds());
        long total = held.stream().mapToLong(s -> s.priceCents).sum();
        var seatDtos = held
            .stream()
            .map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id);
                m.put("row", s.rowLabel);
                m.put("number", s.seatNumber);
                m.put("price", money(s.priceCents));
                m.put("price_cents", s.priceCents);
                return m;
            })
            .toList();
        out.put("holdToken", h.holdToken());
        out.put("hold_token", h.holdToken());
        out.put("holdGroupId", h.holdToken());
        out.put("hold_group_id", h.holdToken());
        out.put("eventId", h.eventId());
        out.put("event_id", h.eventId());
        out.put("seatIds", h.seatIds());
        out.put("seat_ids", h.seatIds());
        out.put("seats", seatDtos);
        out.put("expiresInSeconds", h.expiresInSeconds());
        out.put("expires_in_seconds", h.expiresInSeconds());
        out.put("ttl_seconds", h.expiresInSeconds());
        out.put("expires_at", expires);
        out.put("total_amount", money(total));
        out.put("total_cents", total);
        return out;
    }

    static String money(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
