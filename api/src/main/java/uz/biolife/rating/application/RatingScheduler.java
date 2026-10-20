package uz.biolife.rating.application;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.biolife.shared.time.WorkHours;

/** Monday 00:10 closes the previous week; every workday 18:40 refreshes the current standings. */
@Component
public class RatingScheduler {
    private final WeeklyRatingService weekly;
    private final Clock clock;

    public RatingScheduler(WeeklyRatingService weekly, Clock clock) { this.weekly = weekly; this.clock = clock; }

    @Scheduled(cron = "0 10 0 * * MON", zone = "Asia/Tashkent")
    public void closePreviousWeek() { weekly.compute(WorkHours.weekStart(LocalDate.now(clock)).minusWeeks(1)); }

    @Scheduled(cron = "0 40 18 * * MON-SAT", zone = "Asia/Tashkent")
    public void refreshCurrentWeek() { weekly.compute(WorkHours.weekStart(LocalDate.now(clock))); }
}
