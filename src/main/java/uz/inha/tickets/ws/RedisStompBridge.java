package uz.inha.tickets.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Cross-replica STOMP bridge per spec §7.2.
 *
 * Each backend replica keeps Spring's in-process SimpleBroker, but subscribes to Redis Pub/Sub
 * channel {@code ws.broadcast}. The OutboxPublisher (or any other component) publishes one
 * message per state change; every replica forwards it to its locally-attached STOMP clients
 * via {@link SimpMessagingTemplate}. Net effect: state changes from backend-1 reach clients on
 * backend-2 and vice-versa without an external broker (RabbitMQ/ActiveMQ).
 */
@Configuration
@Profile("!test")
public class RedisStompBridge {

    private static final Logger log = LoggerFactory.getLogger(RedisStompBridge.class);
    public static final String CHANNEL = "ws.broadcast";

    private final SimpMessagingTemplate template;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisStompBridge(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Bean
    RedisMessageListenerContainer wsBridgeContainer(RedisConnectionFactory cf) {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(cf);
        c.addMessageListener(new MessageListenerAdapter((org.springframework.data.redis.connection.MessageListener) (msg, pattern) -> onMessage(msg.getBody())), new PatternTopic(CHANNEL));
        return c;
    }

    void onMessage(byte[] body) {
        if (body == null) return;
        String payload = new String(body, StandardCharsets.UTF_8);
        try {
            JsonNode node = mapper.readTree(payload);
            String eventId = textOrNull(node, "event_id");
            if (eventId == null) eventId = textOrNull(node, "eventId");
            if (eventId == null) {
                log.debug("ws.broadcast without event_id: {}", payload);
                return;
            }
            template.convertAndSend("/topic/events/" + eventId + "/seats", payload);
        } catch (Exception ex) {
            log.warn("ws.broadcast parse-failed: {}", ex.toString());
        }
    }

    static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
