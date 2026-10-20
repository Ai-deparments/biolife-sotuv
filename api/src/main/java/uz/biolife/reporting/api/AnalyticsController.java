package uz.biolife.reporting.api;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.reporting.application.AnalyticsQueries;
import uz.biolife.reporting.application.ReportingDtos.AgentKpi;
import uz.biolife.reporting.application.ReportingDtos.AgentSeries;
import uz.biolife.reporting.application.ReportingDtos.Overview;
import uz.biolife.reporting.application.ReportingDtos.WorkHoursRow;
import uz.biolife.shared.security.CurrentUser;
import uz.biolife.shared.time.WorkHours;

/** Manager analytics. Period defaults to the current week. */
@RestController
@RequestMapping("/api/v1/panel/analytics")
public class AnalyticsController {
    private final AnalyticsQueries queries;
    private final Clock clock;

    public AnalyticsController(AnalyticsQueries queries, Clock clock) { this.queries = queries; this.clock = clock; }

    @GetMapping("/overview")
    public Overview overview(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        user.requireManager();
        return queries.overview(date);
    }

    @GetMapping("/agents")
    public List<AgentKpi> agents(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String region) {
        user.requireManager();
        LocalDate[] p = period(from, to);
        return queries.agents(p[0], p[1], region);
    }

    @GetMapping("/agents/{agentId}")
    public AgentSeries series(CurrentUser user, @PathVariable UUID agentId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        user.requireSelfOrManager(agentId);
        LocalDate[] p = period(from, to);
        return queries.series(agentId, p[0], p[1]);
    }

    @GetMapping("/work-hours")
    public List<WorkHoursRow> workHours(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) String region) {
        user.requireManager();
        LocalDate[] p = period(from, to);
        return queries.workHours(p[0], p[1], region);
    }

    private LocalDate[] period(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate f = from == null ? WorkHours.weekStart(today) : from;
        LocalDate t = to == null ? (from == null ? f.plusDays(5) : today) : to;
        if (t.isBefore(f)) throw new IllegalArgumentException("to < from");
        if (f.plusDays(92).isBefore(t)) throw new IllegalArgumentException("Davr 92 kundan oshmasin");
        return new LocalDate[] {f, t};
    }
}
