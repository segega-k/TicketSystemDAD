package uz.inha.tickets.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.inha.tickets.domain.OutboxEvent;
import uz.inha.tickets.repo.OutboxEventRepository;

/**
 * Outbox dispatcher per spec §5.4 — polls every 200 ms with FOR UPDATE SKIP LOCKED so two backend
 * replicas can share the workload, publishes each row to Redis Pub/Sub channel {@code ws.broadcast},
 * then marks dispatched. Failed rows stay unpublished and the next tick retries.
 */
@Service
@EnableScheduling
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    final OutboxEventRepository repo;
    final StringRedisTemplate redis;

    @PersistenceContext
    EntityManager em;

    @Value("${app.outbox.batch-size:100}")
    int batchSize;

    @Value("${app.outbox.max-attempts-before-poison:10}")
    int maxAttempts;

    public OutboxPublisher(OutboxEventRepository r, StringRedisTemplate t) {
        repo = r;
        redis = t;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:200}")
    @Transactional
    public void publish() {
        @SuppressWarnings("unchecked")
        List<OutboxEvent> batch = em
            .createNativeQuery(
                "select * from outbox_events " +
                "where published_at is null and (attempts is null or attempts < :max) " +
                "order by id " +
                "limit :limit " +
                "for update skip locked",
                OutboxEvent.class
            )
            .setParameter("max", maxAttempts)
            .setParameter("limit", batchSize)
            .getResultList();

        for (OutboxEvent e : batch) {
            try {
                redis.convertAndSend(e.topic != null ? e.topic : "ws.broadcast", e.payload);
                e.publishedAt = Instant.now();
                e.attempts = e.attempts + 1;
            } catch (Exception ex) {
                e.attempts = e.attempts + 1;
                if (e.attempts >= maxAttempts) {
                    // poison-pill: mark as dispatched to stop infinite retry, log loudly
                    e.publishedAt = Instant.now();
                    log.error("outbox-poison id={} attempts={} topic={}: {}", e.id, e.attempts, e.topic, ex.toString());
                } else {
                    log.warn("outbox-publish-failed id={} attempts={}: {}", e.id, e.attempts, ex.toString());
                }
            }
            em.merge(e);
        }
    }

    /** Convenience for components that want to write directly. */
    public OutboxEvent publishAsync(String aggregateType, UUID aggregateId, String eventType, String payload, String traceId) {
        return repo.save(new OutboxEvent("ws.broadcast", aggregateType, aggregateId, eventType, payload, traceId));
    }
}
