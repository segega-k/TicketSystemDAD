package uz.inha.tickets.batch;

import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spring Batch workflow A (spec §10.1) — Daily Sales Aggregation.
 *
 * Cron 02:00 UTC: bookings whose created_at or cancelled_at fell on the previous day are
 * grouped by event and upserted into {@code analytics.event_daily_sales}. Idempotent: rerunning
 * the job on the same date is safe thanks to the ON CONFLICT clause.
 *
 * The schedule trigger is in {@link DailySalesScheduler} to keep this @Configuration free of
 * self-injection cycles.
 */
@Configuration
public class DailySalesJob {

    private static final Logger log = LoggerFactory.getLogger(DailySalesJob.class);

    @Bean
    JdbcTemplate batchJdbc(DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    Tasklet dailySalesTasklet(JdbcTemplate jdbc) {
        return (contribution, chunkContext) -> {
            LocalDate target = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            int rows = jdbc.update(
                "insert into analytics.event_daily_sales (event_id, sales_date, tickets_sold, tickets_cancelled, revenue, refunds_amount) " +
                "select b.event_id, ?::date, " +
                "       sum(case when b.status = 'CONFIRMED' then (select count(*) from booking_seats bs where bs.booking_id = b.id) else 0 end) as tickets_sold, " +
                "       sum(case when b.status = 'CANCELLED' then 1 else 0 end) as tickets_cancelled, " +
                "       coalesce(sum(case when b.status = 'CONFIRMED' then b.total_cents end), 0)::numeric / 100 as revenue, " +
                "       coalesce(sum(case when b.status = 'CANCELLED' then b.refund_cents end), 0)::numeric / 100 as refunds_amount " +
                "from bookings b " +
                "where b.created_at::date = ?::date or b.cancelled_at::date = ?::date " +
                "group by b.event_id " +
                "on conflict (event_id, sales_date) do update set " +
                "  tickets_sold = excluded.tickets_sold, " +
                "  tickets_cancelled = excluded.tickets_cancelled, " +
                "  revenue = excluded.revenue, " +
                "  refunds_amount = excluded.refunds_amount",
                target.toString(),
                target.toString(),
                target.toString()
            );
            log.info("daily-sales: aggregated {} event rows for {}", rows, target);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step dailySalesStep(JobRepository jobRepository, PlatformTransactionManager tx, Tasklet dailySalesTasklet) {
        return new StepBuilder("dailySalesStep", jobRepository)
            .tasklet(dailySalesTasklet, tx)
            .build();
    }

    @Bean(name = "dailySalesBatchJob")
    Job dailySalesBatchJob(JobRepository jobRepository, Step dailySalesStep) {
        return new JobBuilder("dailySalesBatchJob", jobRepository).start(dailySalesStep).build();
    }
}
