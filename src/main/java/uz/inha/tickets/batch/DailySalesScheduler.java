package uz.inha.tickets.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron trigger for the daily-sales batch job. Kept separate from {@link DailySalesJob} so the
 * @Configuration class doesn't self-inject (which Spring detects as a circular reference).
 *
 * Spec §10.1 cron: every day at 02:00 UTC.
 */
@Component
public class DailySalesScheduler {

    private final JobLauncher launcher;
    private final Job job;

    public DailySalesScheduler(JobLauncher launcher, @Qualifier("dailySalesBatchJob") Job job) {
        this.launcher = launcher;
        this.job = job;
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    public void run() throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addLong("ts", System.currentTimeMillis())
            .toJobParameters();
        launcher.run(job, params);
    }
}
