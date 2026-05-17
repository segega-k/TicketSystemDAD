package uz.inha.tickets.batch;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.inha.tickets.domain.OutboxEvent;
import uz.inha.tickets.repo.OutboxEventRepository;

/**
 * Spring Batch workflow B (spec §10.2) — Outbox Reconciliation.
 *
 * Cron every 10 minutes: finds outbox_events still {@code published_at IS NULL} and older than
 * one minute, republishes via the same Redis Pub/Sub channel, and marks poison-pills once
 * attempts >= max. Surfaces {@code outbox_poison_total} and {@code OutboxBacklogHigh} signals
 * via simple log lines (Grafana alerts wire these up).
 *
 * Implemented as @Scheduled (not Spring Batch chunk) because the workload is small, the SQL
 * already filters via the partial index and SKIP LOCKED isn't needed at this cadence.
 */
@Component
public class OutboxReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxReconciliationJob.class);

    private final OutboxEventRepository repo;
    private final StringRedisTemplate redis;

    @Value("${app.outbox.max-attempts-before-poison:10}")
    int maxAttempts;

    @Value("${app.outbox.backlog-alert-threshold:1000}")
    long backlogThreshold;

    public OutboxReconciliationJob(OutboxEventRepository repo, StringRedisTemplate redis) {
        this.repo = repo;
        this.redis = redis;
    }

    /** Spec §10.2 cron: every 10 minutes. */
    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void run() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
        long backlog = repo.countByPublishedAtIsNull();
        if (backlog > backlogThreshold) {
            log.error("OutboxBacklogHigh: undispatched={}, threshold={}", backlog, backlogThreshold);
        }
        var rows = repo.findTop100ByPublishedAtIsNullAndCreatedAtBeforeOrderByCreatedAtAsc(cutoff);
        int retried = 0, poisoned = 0;
        for (OutboxEvent e : rows) {
            try {
                redis.convertAndSend(e.topic != null ? e.topic : "ws.broadcast", e.payload);
                e.publishedAt = Instant.now();
                e.attempts = e.attempts + 1;
                retried++;
            } catch (Exception ex) {
                e.attempts = e.attempts + 1;
                if (e.attempts >= maxAttempts) {
                    e.publishedAt = Instant.now();
                    poisoned++;
                    log.error("outbox-poison id={} attempts={}: {}", e.id, e.attempts, ex.toString());
                }
            }
        }
        repo.saveAll(rows);
        if (retried + poisoned > 0) {
            log.info("outbox-reconciliation: retried={} poisoned={} backlog={}", retried, poisoned, backlog);
        }
    }
}
