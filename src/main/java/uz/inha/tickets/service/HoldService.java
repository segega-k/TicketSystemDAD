package uz.inha.tickets.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import uz.inha.tickets.domain.Enums.SeatStatus;
import uz.inha.tickets.domain.Seat;
import uz.inha.tickets.repo.BookingSeatRepository;
import uz.inha.tickets.repo.EventRepository;
import uz.inha.tickets.repo.SeatRepository;

@Service
public class HoldService {

    final StringRedisTemplate redis;
    final SeatRepository seats;
    final EventRepository events;
    final BookingSeatRepository booked;
    final SimpMessagingTemplate ws;

    @Value("${app.holds.ttl-seconds}")
    long ttl;

    @Value("${app.holds.max-per-user-event}")
    int cap;

    @Value("${app.rate-limit.holds-per-minute}")
    int rpm;

    @Value("${app.redis.fail-closed:true}")
    boolean failClosed;

    final Map<String, MemHold> mem = new ConcurrentHashMap<>();
    final Map<UUID, Deque<Instant>> memRate = new ConcurrentHashMap<>();

    record MemHold(UUID user, UUID event, List<UUID> seats, Instant exp) {}

    public record HoldResult(String holdToken, UUID userId, UUID eventId, List<UUID> seatIds, long expiresInSeconds) {}

    record HoldGroup(UUID user, UUID event, List<UUID> seats) {}

    public HoldService(
        StringRedisTemplate r,
        SeatRepository s,
        EventRepository e,
        BookingSeatRepository b,
        SimpMessagingTemplate w
    ) {
        redis = r;
        seats = s;
        events = e;
        booked = b;
        ws = w;
    }

    public HoldResult hold(UUID user, UUID event, List<UUID> seatIds) {
        cleanup();
        tryRate(user);
        if (seatIds == null || seatIds.size() < 1 || seatIds.size() > 6) throw DomainException.bad(
            "hold requires 1-6 seats"
        );
        if (new HashSet<>(seatIds).size() != seatIds.size()) throw DomainException.bad(
            "duplicate seats are not allowed"
        );
        validateAdjacency(event, seatIds);
        HoldResult existing = existingEquivalent(user, event, seatIds);
        if (existing != null) return existing;

        Long redisPurchaseCount = redisPurchaseCount(user, event);
        long already = redisPurchaseCount != null
            ? redisPurchaseCount + memHeldByUser(user, event)
            : booked.countByBookingUserIdAndEventId(user, event) + activeHeldByUser(user, event);
        if (already + seatIds.size() > cap) throw DomainException.unprocessable("per-user event cap exceeded");

        String token = UUID.randomUUID().toString();
        try {
            List<String> keys = new ArrayList<>();
            keys.add(groupKey(token));
            keys.add(countKey(user, event));
            for (UUID s : seatIds) keys.add(seatKey(s));
            String csv = seatIds.stream().map(UUID::toString).collect(Collectors.joining(","));
            Long code = redis.execute(
                new DefaultRedisScript<>(script("redis/acquire_hold.lua"), Long.class),
                keys,
                user.toString(),
                event.toString(),
                token,
                csv,
                String.valueOf(ttl),
                String.valueOf(cap)
            );
            if (Objects.equals(code, -2L)) throw DomainException.unprocessable("per-user event cap exceeded");
            if (!Objects.equals(code, 1L)) throw DomainException.conflict("one or more seats already held").prop(
                "conflicting_seat_ids",
                seatIds
            );
        } catch (DomainException e) {
            throw e;
        } catch (Exception ex) {
            if (failClosed) throw DomainException.unavailable("redis unavailable");
            long memAlready = booked.countByBookingUserIdAndEventId(user, event) + activeHeldByUser(user, event);
            if (memAlready + seatIds.size() > cap) throw DomainException.unprocessable("per-user event cap exceeded");
            for (MemHold h : mem.values()) {
                if (h.event.equals(event) && h.exp.isAfter(Instant.now()) && !Collections.disjoint(h.seats, seatIds)) {
                    throw DomainException.conflict("one or more seats already held").prop(
                        "conflicting_seat_ids",
                        seatIds
                    );
                }
            }
            mem.put(token, new MemHold(user, event, List.copyOf(seatIds), Instant.now().plusSeconds(ttl)));
        }
        ws.convertAndSend("/topic/events/" + event + "/seats", Map.of("type", "HELD", "seatIds", seatIds));
        return new HoldResult(token, user, event, List.copyOf(seatIds), ttl);
    }

    public void release(UUID user, String token) {
        HoldResult h = verify(user, token);
        releaseVerified(h);
        decrementCount(h);
    }

    /** Deletes hold keys after a successful booking. The user/event count is intentionally preserved as owned seats. */
    public void releaseVerified(HoldResult h) {
        try {
            redis.delete(groupKey(h.holdToken));
            h.seatIds.forEach(s -> redis.delete(seatKey(s)));
        } catch (Exception e) {
            if (failClosed && !mem.containsKey(h.holdToken)) throw DomainException.unavailable("redis unavailable");
        }
        mem.remove(h.holdToken);
        ws.convertAndSend("/topic/events/" + h.eventId + "/seats", Map.of("type", "RELEASED", "seatIds", h.seatIds));
    }

    public HoldResult verify(UUID user, String token) {
        cleanup();
        if (token == null || token.isBlank()) throw DomainException.notFound("hold not found or expired");
        try {
            Map<Object, Object> g = redis.opsForHash().entries(groupKey(token));
            if (g != null && !g.isEmpty()) {
                HoldGroup hg = parseGroup(g);
                if (!hg.user.equals(user)) throw DomainException.forbidden("hold belongs to another user");
                return new HoldResult(
                    token,
                    hg.user,
                    hg.event,
                    hg.seats,
                    Math.max(0, redis.getExpire(groupKey(token)))
                );
            }
            String legacy = redis.opsForValue().get("holdgroup:" + token);
            if (legacy != null) {
                HoldGroup hg = parseLegacyGroup(legacy);
                if (!hg.user.equals(user)) throw DomainException.forbidden("hold belongs to another user");
                return new HoldResult(
                    token,
                    hg.user,
                    hg.event,
                    hg.seats,
                    Math.max(0, redis.getExpire("holdgroup:" + token))
                );
            }
        } catch (DomainException e) {
            throw e;
        } catch (Exception ignored) {}
        MemHold h = mem.get(token);
        if (h == null || h.exp.isBefore(Instant.now())) throw DomainException.notFound("hold not found or expired");
        if (!h.user.equals(user)) throw DomainException.forbidden("hold belongs to another user");
        return new HoldResult(
            token,
            h.user,
            h.event,
            h.seats,
            Math.max(0, Duration.between(Instant.now(), h.exp).toSeconds())
        );
    }

    public Set<UUID> activeHeldSeatIds(UUID event) {
        cleanup();
        Set<UUID> out = new LinkedHashSet<>();
        mem
            .values()
            .stream()
            .filter(h -> h.event.equals(event) && h.exp.isAfter(Instant.now()))
            .forEach(h -> out.addAll(h.seats));
        try {
            Set<String> groups = redis.keys("hold:group:*");
            if (groups != null) for (String key : groups) {
                Map<Object, Object> g = redis.opsForHash().entries(key);
                if (g != null && !g.isEmpty()) {
                    HoldGroup hg = parseGroup(g);
                    if (hg.event.equals(event)) out.addAll(hg.seats);
                }
            }
            Set<String> legacy = redis.keys("hold:" + event + ":*");
            if (legacy != null) for (String k : legacy) out.add(
                UUID.fromString(k.substring(("hold:" + event + ":").length()))
            );
        } catch (Exception ignored) {}
        return out;
    }

    void validateAdjacency(UUID event, List<UUID> ids) {
        if (!events.existsById(event)) throw DomainException.notFound("event not found");
        List<Seat> ss = seats.findByIdIn(ids);
        if (ss.size() != ids.size()) throw DomainException.notFound("seat not found");
        if (ss.stream().anyMatch(s -> !s.event.id.equals(event))) throw DomainException.bad("seat not in event");
        if (ss.stream().anyMatch(s -> s.status != SeatStatus.AVAILABLE)) throw DomainException.conflict(
            "one or more seats already booked"
        );
        if (!Collections.disjoint(booked.bookedSeatIds(event), ids)) throw DomainException.conflict(
            "one or more seats already booked"
        );
        String sec = ss.getFirst().section, row = ss.getFirst().rowLabel;
        if (ss.stream().anyMatch(s -> !s.section.equals(sec) || !s.rowLabel.equals(row))) throw DomainException.bad(
            "seats must be in same section and row"
        );
        List<Integer> nums = ss.stream().map(s -> s.seatNumber).sorted().toList();
        for (int i = 1; i < nums.size(); i++) if (nums.get(i) != nums.get(i - 1) + 1) throw DomainException.bad(
            "seats must be adjacent"
        );
    }

    long activeHeldByUser(UUID user, UUID event) {
        return memHeldByUser(user, event) + redisHeldByUser(user, event);
    }

    long memHeldByUser(UUID user, UUID event) {
        return mem
            .values()
            .stream()
            .filter(h -> h.user.equals(user) && h.event.equals(event) && h.exp.isAfter(Instant.now()))
            .mapToLong(h -> h.seats.size())
            .sum();
    }

    Long redisPurchaseCount(UUID user, UUID event) {
        try {
            String val = redis.opsForValue().get(countKey(user, event));
            return val == null ? null : Long.parseLong(val);
        } catch (Exception ignored) {
            return null;
        }
    }

    long redisHeldByUser(UUID user, UUID event) {
        try {
            Set<String> groups = redis.keys("hold:group:*");
            if (groups == null) return 0;
            long c = 0;
            for (String key : groups) {
                Map<Object, Object> g = redis.opsForHash().entries(key);
                if (g == null || g.isEmpty()) continue;
                HoldGroup hg = parseGroup(g);
                if (hg.user.equals(user) && hg.event.equals(event)) c += hg.seats.size();
            }
            return c;
        } catch (Exception ignored) {
            return 0;
        }
    }

    void cleanup() {
        mem.entrySet().removeIf(e -> e.getValue().exp.isBefore(Instant.now()));
    }

    void tryRate(UUID user) {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
        Deque<Instant> q = memRate.computeIfAbsent(user, u -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.removeFirst();
            if (q.size() >= rpm) throw DomainException.tooManyRequests("hold rate limit exceeded");
            q.addLast(Instant.now());
        }
        try {
            // Token bucket: capacity = rpm, refill = rpm tokens per minute (rpm/60 per second)
            double refillPerSecond = rpm / 60.0;
            Long retry = redis.execute(
                new DefaultRedisScript<>(script("redis/token_bucket.lua"), Long.class),
                List.of("ratelimit:hold:" + user),
                String.valueOf(rpm),
                String.valueOf(refillPerSecond),
                String.valueOf(System.currentTimeMillis())
            );
            if (retry != null && retry > 0) throw DomainException.tooManyRequests("hold rate limit exceeded").prop(
                "retry_after_seconds",
                retry
            );
        } catch (DomainException e) {
            throw e;
        } catch (Exception ex) {
            if (failClosed) throw DomainException.unavailable("redis unavailable");
        }
    }

    HoldResult existingEquivalent(UUID user, UUID event, List<UUID> seatIds) {
        Set<UUID> req = new LinkedHashSet<>(seatIds);
        for (var e : mem.entrySet()) {
            MemHold h = e.getValue();
            if (
                h.user.equals(user) &&
                h.event.equals(event) &&
                h.exp.isAfter(Instant.now()) &&
                new LinkedHashSet<>(h.seats).equals(req)
            ) {
                return new HoldResult(
                    e.getKey(),
                    h.user,
                    event,
                    h.seats,
                    Math.max(0, Duration.between(Instant.now(), h.exp).toSeconds())
                );
            }
        }
        try {
            Set<String> groups = redis.keys("hold:group:*");
            if (groups != null) for (String key : groups) {
                Map<Object, Object> g = redis.opsForHash().entries(key);
                if (g == null || g.isEmpty()) continue;
                HoldGroup hg = parseGroup(g);
                if (hg.user.equals(user) && hg.event.equals(event) && new LinkedHashSet<>(hg.seats).equals(req)) {
                    return new HoldResult(
                        key.substring("hold:group:".length()),
                        hg.user,
                        event,
                        hg.seats,
                        Math.max(0, redis.getExpire(key))
                    );
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    void decrementCount(HoldResult h) {
        try {
            redis.opsForValue().decrement(countKey(h.userId, h.eventId), h.seatIds.size());
        } catch (Exception ignored) {}
    }

    HoldGroup parseGroup(Map<Object, Object> g) {
        Object user = g.get("user_id"), event = g.get("event_id"), seatIds = g.get("seat_ids");
        if (user == null || event == null || seatIds == null) throw DomainException.notFound(
            "hold not found or expired"
        );
        return new HoldGroup(
            UUID.fromString(String.valueOf(user)),
            UUID.fromString(String.valueOf(event)),
            parseSeats(String.valueOf(seatIds))
        );
    }

    HoldGroup parseLegacyGroup(String g) {
        String[] p = g.split("\\|", 3);
        if (p.length < 3) throw DomainException.notFound("hold not found or expired");
        return new HoldGroup(UUID.fromString(p[0]), UUID.fromString(p[1]), parseSeats(p[2]));
    }

    List<UUID> parseSeats(String csv) {
        return Arrays.stream(csv.split(",")).filter(x -> !x.isBlank()).map(UUID::fromString).toList();
    }

    static String groupKey(String token) {
        return "hold:group:" + token;
    }

    static String seatKey(UUID seat) {
        return "hold:seat:" + seat;
    }

    static String countKey(UUID user, UUID event) {
        return "purchase:count:" + user + ":" + event;
    }

    static String script(String path) {
        try {
            return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("missing redis script " + path, e);
        }
    }
}
