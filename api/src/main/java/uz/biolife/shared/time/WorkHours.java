package uz.biolife.shared.time;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Company working window: 09:00–18:00, Monday–Saturday, Asia/Tashkent.
 * Pure value object so reporting rules are testable without Spring.
 */
public final class WorkHours {
    public static final ZoneId ZONE = ZoneId.of("Asia/Tashkent");
    public static final LocalTime START = LocalTime.of(9, 0);
    public static final LocalTime END = LocalTime.of(18, 0);
    /** Arriving after this counts as a late start. */
    public static final LocalTime LATE_AFTER = LocalTime.of(9, 30);
    /** Leaving before this counts as an early finish. */
    public static final LocalTime EARLY_BEFORE = LocalTime.of(17, 30);

    private WorkHours() {}

    public static boolean isWorkDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    public static LocalDate dateOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    public static LocalTime timeOf(Instant instant) {
        return instant.atZone(ZONE).toLocalTime();
    }

    public static Instant windowStart(LocalDate date) {
        return ZonedDateTime.of(date, START, ZONE).toInstant();
    }

    public static Instant windowEnd(LocalDate date) {
        return ZonedDateTime.of(date, END, ZONE).toInstant();
    }

    /** Minutes of [from, to] that fall inside the working window of the given day. */
    public static int overlapMinutes(LocalDate date, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) return 0;
        Instant s = from.isAfter(windowStart(date)) ? from : windowStart(date);
        Instant e = to.isBefore(windowEnd(date)) ? to : windowEnd(date);
        return e.isAfter(s) ? (int) Duration.between(s, e).toMinutes() : 0;
    }

    public static boolean isLateStart(Instant firstActivity) {
        return firstActivity != null && timeOf(firstActivity).isAfter(LATE_AFTER);
    }

    public static boolean isEarlyFinish(Instant lastActivity) {
        return lastActivity != null && timeOf(lastActivity).isBefore(EARLY_BEFORE);
    }

    /** Monday of the ISO week containing the date. */
    public static LocalDate weekStart(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }
}
