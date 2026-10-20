package uz.biolife.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.biolife.reporting.application.ReportingDtos.AgentKpi;
import uz.biolife.reporting.application.ReportingDtos.AgentSeries;
import uz.biolife.reporting.application.ReportingDtos.DayRow;
import uz.biolife.reporting.application.ReportingDtos.Overview;
import uz.biolife.reporting.application.ReportingDtos.WorkHoursRow;
import uz.biolife.reporting.domain.AgentDay;
import uz.biolife.reporting.domain.AgentRef;
import uz.biolife.reporting.infra.AgentDayRepository;
import uz.biolife.reporting.infra.ReportAgentRepository;
import uz.biolife.shared.time.WorkHours;
import uz.biolife.shared.web.NotFoundException;

/** Read side of reporting: overview tiles, per-agent KPIs, daily series, work-hours discipline. */
@Service
public class AnalyticsQueries {
    private final ReportAgentRepository agents;
    private final AgentDayRepository days;
    private final Clock clock;

    public AnalyticsQueries(ReportAgentRepository agents, AgentDayRepository days, Clock clock) {
        this.agents = agents; this.days = days; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview(LocalDate date) {
        LocalDate d = date == null ? LocalDate.now(clock) : date;
        return summary(d, d);
    }

    /** The overview tiles summed over a period (weekly report). */
    @Transactional(readOnly = true)
    public Overview summary(LocalDate from, LocalDate to) {
        List<AgentDay> rows = from.equals(to) ? days.findByDate(from) : days.findByDateBetween(from, to);
        int total = agents.findByActiveTrue().size();
        int active = (int) rows.stream().filter(r -> r.getFirstActivity() != null).map(AgentDay::getAgentId).distinct().count();
        int planned = sum(rows, AgentDay::getPlanned), started = sum(rows, AgentDay::getStarted), closed = sum(rows, AgentDay::getClosed);
        int proofs = sum(rows, AgentDay::getProofsComplete), scoreSum = sum(rows, AgentDay::getScoreSum), scoreCount = sum(rows, AgentDay::getScoreCount);
        int promo = sum(rows, AgentDay::getPromoCount), photos = sum(rows, AgentDay::getPhotosTotal), photosOk = sum(rows, AgentDay::getPhotosOk);
        return new Overview(from, active, total, planned, started, closed, pct(proofs, closed),
            sum(rows, AgentDay::getExcused), sum(rows, AgentDay::getSuspect),
            scoreCount == 0 ? 0 : Math.round((float) scoreSum / scoreCount), pct(promo, scoreCount), pct(photosOk, photos),
            (int) rows.stream().filter(AgentDay::isLateStart).count(), (int) rows.stream().filter(AgentDay::isEarlyFinish).count(),
            rows.stream().mapToLong(AgentDay::getSalesUzs).sum(), sum(rows, AgentDay::getBlocks), scoreCount, photos);
    }

    @Transactional(readOnly = true)
    public List<AgentKpi> agents(LocalDate from, LocalDate to, String region) {
        Map<UUID, List<AgentDay>> byAgent = days.findByDateBetween(from, to).stream().collect(Collectors.groupingBy(AgentDay::getAgentId));
        List<AgentKpi> out = new ArrayList<>();
        for (AgentRef a : agents.findByActiveTrue()) {
            if (region != null && !region.isBlank() && !region.equalsIgnoreCase(a.getRegion())) continue;
            out.add(kpi(a, byAgent.getOrDefault(a.getId(), List.of())));
        }
        out.sort(Comparator.comparingInt(AgentKpi::avgScore).reversed().thenComparing(AgentKpi::name));
        return out;
    }

    @Transactional(readOnly = true)
    public AgentSeries series(UUID agentId, LocalDate from, LocalDate to) {
        AgentRef a = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent topilmadi"));
        List<DayRow> rows = days.findByAgentIdAndDateBetweenOrderByDateAsc(agentId, from, to).stream()
            .map(d -> new DayRow(d.getDate(), d.getPlanned(), d.getClosed(), d.getExcused(), d.getSuspect(), d.avgScore(), d.promoRate(),
                d.getPhotosOk(), d.getPhotosTotal(), d.getBlocks(), d.getFirstActivity(), d.getLastActivity(), d.getActiveMinutes(), d.isLateStart(), d.isEarlyFinish()))
            .toList();
        return new AgentSeries(agentId, a.getName(), from, to, rows);
    }

    @Transactional(readOnly = true)
    public List<WorkHoursRow> workHours(LocalDate from, LocalDate to, String region) {
        Map<UUID, List<AgentDay>> byAgent = days.findByDateBetween(from, to).stream().collect(Collectors.groupingBy(AgentDay::getAgentId));
        int plannedDays = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) if (WorkHours.isWorkDay(d)) plannedDays++;
        List<WorkHoursRow> out = new ArrayList<>();
        for (AgentRef a : agents.findByActiveTrue()) {
            if (region != null && !region.isBlank() && !region.equalsIgnoreCase(a.getRegion())) continue;
            List<AgentDay> worked = byAgent.getOrDefault(a.getId(), List.of()).stream().filter(d -> d.getFirstActivity() != null).toList();
            out.add(new WorkHoursRow(a.getId(), a.getName(), a.getRegion(), worked.size(), plannedDays,
                avgTime(worked, true), avgTime(worked, false),
                worked.isEmpty() ? 0 : (int) worked.stream().mapToInt(AgentDay::getActiveMinutes).average().orElse(0),
                (int) worked.stream().filter(AgentDay::isLateStart).count(), (int) worked.stream().filter(AgentDay::isEarlyFinish).count()));
        }
        out.sort(Comparator.comparingInt(WorkHoursRow::lateStarts).reversed().thenComparing(WorkHoursRow::name));
        return out;
    }

    private AgentKpi kpi(AgentRef a, List<AgentDay> rows) {
        int planned = sum(rows, AgentDay::getPlanned), closed = sum(rows, AgentDay::getClosed), proofs = sum(rows, AgentDay::getProofsComplete);
        int scoreSum = sum(rows, AgentDay::getScoreSum), scoreCount = sum(rows, AgentDay::getScoreCount), promo = sum(rows, AgentDay::getPromoCount);
        int photos = sum(rows, AgentDay::getPhotosTotal), photosOk = sum(rows, AgentDay::getPhotosOk);
        List<AgentDay> worked = rows.stream().filter(d -> d.getFirstActivity() != null).toList();
        return new AgentKpi(a.getId(), a.getName(), a.getRegion(), worked.size(), planned, closed, pct(closed, planned), pct(proofs, closed),
            sum(rows, AgentDay::getExcused), sum(rows, AgentDay::getSuspect), scoreCount,
            scoreCount == 0 ? 0 : Math.round((float) scoreSum / scoreCount), pct(promo, scoreCount), photos, pct(photosOk, photos),
            sum(rows, AgentDay::getBlocks), rows.stream().mapToLong(AgentDay::getSalesUzs).sum(),
            worked.isEmpty() ? 0 : (int) worked.stream().mapToInt(AgentDay::getActiveMinutes).average().orElse(0),
            (int) worked.stream().filter(AgentDay::isLateStart).count(), (int) worked.stream().filter(AgentDay::isEarlyFinish).count());
    }

    private static String avgTime(List<AgentDay> worked, boolean first) {
        if (worked.isEmpty()) return null;
        double avgSec = worked.stream().mapToInt(d -> WorkHours.timeOf(first ? d.getFirstActivity() : d.getLastActivity()).toSecondOfDay()).average().orElse(0);
        LocalTime t = LocalTime.ofSecondOfDay((long) avgSec);
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }
    private static int sum(List<AgentDay> rows, Function<AgentDay, Integer> f) { return rows.stream().mapToInt(f::apply).sum(); }
    private static int pct(int part, int whole) { return whole == 0 ? 0 : Math.round(100f * part / whole); }
}
