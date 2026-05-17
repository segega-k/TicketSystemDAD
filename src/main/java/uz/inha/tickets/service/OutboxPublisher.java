package uz.inha.tickets.service;

import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import uz.inha.tickets.repo.OutboxEventRepository;

@Service
@EnableScheduling
public class OutboxPublisher {

    final OutboxEventRepository repo;
    final StringRedisTemplate redis;

    public OutboxPublisher(OutboxEventRepository r, StringRedisTemplate t) {
        repo = r;
        redis = t;
    }

    @Scheduled(fixedDelay = 5000)
    public void publish() {
        for (var e : repo.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            try {
                redis.convertAndSend(e.topic, e.payload);
            } catch (Exception ignored) {}
            e.publishedAt = Instant.now();
            repo.save(e);
        }
    }
}
