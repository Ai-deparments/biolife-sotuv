package uz.biolife.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReportingDtos {
    private ReportingDtos() {}

    /**
     * Panel header tiles for one day (or a period: then agentsActive = distinct agents who worked, late/early = agent-days).
     * {@code conversations} and {@code photosTotal} tell the panel whether avgScore/promoRate/photosOkRate have data at all.
     */
    public record Overview(LocalDate date, int agentsActive, int agentsTotal, int planned, int started, int closed,
                           int proofsCompleteRate, int excused, int suspect, int avgScore, int promoRate,
                           int photosOkRate, int lateStarts, int earlyFinishes, long salesUzs, int blocks,
                           int conversations, int photosTotal) {}

    /** KPIs of one agent over a period ("agent kesimida"). */
    public record AgentKpi(UUID agentId, String name, String region, int daysWorked, int planned, int closed, int completionRate,
                           int proofsCompleteRate, int excused, int suspect, int conversations, int avgScore, int promoRate,
                           int photosTotal, int photosOkRate, int blocks, long salesUzs,
                           int avgActiveMinutes, int lateStarts, int earlyFinishes) {}

    public record DayRow(LocalDate date, int planned, int closed, int excused, int suspect, int avgScore, int promoRate,
                         int photosOk, int photosTotal, int blocks, Instant firstActivity, Instant lastActivity,
                         int activeMinutes, boolean lateStart, boolean earlyFinish) {}

    public record AgentSeries(UUID agentId, String name, LocalDate from, LocalDate to, List<DayRow> days) {}

    /** Work-hours discipline per agent. */
    public record WorkHoursRow(UUID agentId, String name, String region, int daysWorked, int daysPlanned,
                               String avgFirstActivity, String avgLastActivity, int avgActiveMinutes, int lateStarts, int earlyFinishes) {}

    public record DeliveryDto(UUID id, String type, LocalDate periodStart, LocalDate periodEnd, String channel, String recipient,
                              String status, String error, Instant sentAt, String body) {}
}
