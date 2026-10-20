package uz.biolife.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.biolife.reporting.domain.ReportDelivery;
import uz.biolife.reporting.infra.ReportDeliveryRepository;
import uz.biolife.shared.time.WorkHours;

/** Composes, sends and records a report. Failures are recorded, never swallowed silently. */
@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private final AnalyticsQueries analytics;
    private final ReportComposer composer;
    private final ReportSender sender;
    private final ReportDeliveryRepository deliveries;
    private final Clock clock;

    public ReportService(AnalyticsQueries analytics, ReportComposer composer, ReportSender sender, ReportDeliveryRepository deliveries, Clock clock) {
        this.analytics = analytics; this.composer = composer; this.sender = sender; this.deliveries = deliveries; this.clock = clock;
    }

    @Transactional
    public ReportDelivery sendDaily(LocalDate date) {
        Report r = composer.daily(analytics.overview(date), analytics.agents(date, date, null), analytics.workHours(date, date, null));
        return deliver(ReportDelivery.Type.DAILY, date, date, r);
    }

    @Transactional
    public ReportDelivery sendWeekly(LocalDate anyDayOfWeek) {
        LocalDate from = WorkHours.weekStart(anyDayOfWeek), to = from.plusDays(5);
        Report r = composer.weekly(from, to, analytics.summary(from, to), analytics.agents(from, to, null), analytics.workHours(from, to, null));
        return deliver(ReportDelivery.Type.WEEKLY, from, to, r);
    }

    /** Channel the reports go to now: "telegram" or "log" (no bot configured yet). */
    public String channel() { return sender.channel(); }

    private ReportDelivery deliver(ReportDelivery.Type type, LocalDate from, LocalDate to, Report report) {
        ReportDelivery.Status status = ReportDelivery.Status.SENT;
        String error = null;
        try { sender.send(report); }
        catch (RuntimeException e) { status = ReportDelivery.Status.FAILED; error = e.getMessage(); log.error("report send failed", e); }
        return deliveries.save(new ReportDelivery(type, from, to, sender.channel(), sender.recipient(), report.text(), status,
            error == null ? null : error.length() > 900 ? error.substring(0, 900) : error, clock.instant()));
    }
}
