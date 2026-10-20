package uz.biolife.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily at 18:30 Mon–Sat, weekly Monday 09:00 for the previous week. */
@Component
public class ReportScheduler {
    private final ReportService reports;
    private final Clock clock;

    public ReportScheduler(ReportService reports, Clock clock) { this.reports = reports; this.clock = clock; }

    @Scheduled(cron = "0 30 18 * * MON-SAT", zone = "Asia/Tashkent")
    public void daily() { reports.sendDaily(LocalDate.now(clock)); }

    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Tashkent")
    public void weekly() { reports.sendWeekly(LocalDate.now(clock).minusWeeks(1)); }
}
