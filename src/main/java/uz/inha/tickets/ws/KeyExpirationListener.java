package uz.inha.tickets.ws;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import uz.inha.tickets.domain.OutboxEvent;
import uz.inha.tickets.repo.OutboxEventRepository;
import uz.inha.tickets.repo.SeatRepository;

/**
 * Listens to Redis key-expiration events for {@code hold:seat:*} (spec §5.5).
 * When a hold expires Redis emits {@code __keyevent@0__:expired} carrying the key name; we
 * persist a SEAT_AVAILABLE outbox row so connected WebSocket clients flip the seat from HELD
 * to AVAILABLE within ~100 ms instead of waiting up to the cache TTL.
 */
@Configuration
@Profile("!test")
public class KeyExpirationListener {

    private static final Logger log = LoggerFactory.getLogger(KeyExpirationListener.class);

    private final OutboxEventRepository outbox;
    private final SeatRepository seats;
    private final StringRedisTemplate redis;

    public KeyExpirationListener(OutboxEventRepository outbox, SeatRepository seats, StringRedisTemplate redis) {
        this.outbox = outbox;
        this.seats = seats;
        this.redis = redis;
    }

    @Bean
    RedisMessageListenerContainer keyExpirationContainer(RedisConnectionFactory cf) {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(cf);
        c.addMessageListener(
            new MessageListenerAdapter(
                (org.springframework.data.redis.connection.MessageListener) (msg, pattern) -> onMessage(new String(msg.getBody()))
            ),
            new PatternTopic("__keyevent@0__:expired")
        );
        return c;
    }

    void onMessage(String key) {
        if (key == null || !key.startsWith("hold:seat:")) return;
        String rest = key.substring("hold:seat:".length());
        try {
            UUID seatId = UUID.fromString(rest);
            seats
                .findById(seatId)
                .ifPresent(seat -> {
                    String payload = "{\"type\":\"AVAILABLE\",\"event_id\":\"" + seat.event.id +
                        "\",\"seat_id\":\"" + seat.id + "\",\"status\":\"AVAILABLE\"}";
                    outbox.save(new OutboxEvent("ws.broadcast", "Seat", seat.id, "SEAT_AVAILABLE", payload, null));
                });
        } catch (IllegalArgumentException ex) {
            log.debug("ignored non-uuid expired key: {}", key);
        } catch (Exception ex) {
            log.warn("KeyExpirationListener failed for key {}: {}", key, ex.toString());
        }
    }
}
