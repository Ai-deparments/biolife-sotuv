package uz.biolife.reporting.application;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import uz.biolife.reporting.domain.AgentDay;
import uz.biolife.reporting.domain.AgentRef;
import uz.biolife.reporting.domain.EventSeen;
import uz.biolife.reporting.infra.AgentDayRepository;
import uz.biolife.reporting.infra.ReportAgentRepository;
import uz.biolife.reporting.infra.EventSeenRepository;
import uz.biolife.shared.events.DomainEvents.AgentProfileChanged;
import uz.biolife.shared.events.DomainEvents.AnalysisCompleted;
import uz.biolife.shared.events.DomainEvents.DayPlanned;
import uz.biolife.shared.events.DomainEvents.OrderConfirmed;
import uz.biolife.shared.events.DomainEvents.PhotoChecked;
import uz.biolife.shared.events.DomainEvents.VisitClosed;
import uz.biolife.shared.events.DomainEvents.VisitStarted;
import uz.biolife.shared.persistence.AggregateLock;
import uz.biolife.shared.time.WorkHours;

/** Folds events into report_agent_day. Each visit-level event is applied once (report_event_seen). */
@Component
public class ReportingFactListener {
    private final ReportAgentRepository agents;
    private final AgentDayRepository days;
    private final EventSeenRepository seen;
    private final AggregateLock lock;

    public ReportingFactListener(ReportAgentRepository agents, AgentDayRepository days, EventSeenRepository seen, AggregateLock lock) {
        this.agents = agents; this.days = days; this.seen = seen; this.lock = lock;
    }

    @ApplicationModuleListener
    public void on(AgentProfileChanged e) {
        lock.acquire("report:agent:" + e.agentId());
        agents.findById(e.agentId()).ifPresentOrElse(
            a -> a.update(e.name(), e.region(), e.district(), e.active()),
            () -> agents.save(new AgentRef(e.agentId(), e.name(), e.region(), e.district(), e.active())));
    }

    @ApplicationModuleListener
    public void on(DayPlanned e) {
        AgentDay d = day(e.agentId(), e.date());
        d.setPlanned(e.plannedVisits());
        days.save(d);
    }

    @ApplicationModuleListener
    public void on(VisitStarted e) {
        lock.acquire("report:day:" + e.agentId() + ":" + WorkHours.dateOf(e.startedAt()));
        if (!first("started", e.visitId())) return;
        AgentDay d = day(e.agentId(), WorkHours.dateOf(e.startedAt()));
        d.addStarted();
        d.touch(e.startedAt());
        days.save(d);
    }

    @ApplicationModuleListener
    public void on(VisitClosed e) {
        lock.acquire("report:day:" + e.agentId() + ":" + e.date());
        if (!first("closed", e.visitId())) return;
        AgentDay d = day(e.agentId(), e.date());
        d.addClosed(e.proofsComplete(), e.excused(), e.suspect(), e.blocks());
        if (e.closedAt() != null) d.touch(e.closedAt());
        days.save(d);
    }

    @ApplicationModuleListener
    public void on(AnalysisCompleted e) {
        lock.acquire("report:day:" + e.agentId() + ":" + e.date());
        if (!first("analysis", e.analysisId())) return;
        AgentDay d = day(e.agentId(), e.date());
        d.addScore(e.score(), e.promoMentioned());
        days.save(d);
    }

    @ApplicationModuleListener
    public void on(PhotoChecked e) {
        lock.acquire("report:day:" + e.agentId() + ":" + e.date());
        if (!first("photo", e.visitId())) return;
        AgentDay d = day(e.agentId(), e.date());
        d.addPhoto("ok".equalsIgnoreCase(e.verdict()));
        days.save(d);
    }

    @ApplicationModuleListener
    public void on(OrderConfirmed e) {
        lock.acquire("report:day:" + e.agentId() + ":" + e.date());
        if (!first("order", e.visitId())) return;
        AgentDay d = day(e.agentId(), e.date());
        d.addSales(e.amountUzs());
        days.save(d);
    }

    /** Locks the agent-day first so concurrent events for the same day cannot both insert it. */
    private AgentDay day(UUID agentId, LocalDate date) {
        lock.acquire("report:day:" + agentId + ":" + date);
        return days.findByAgentIdAndDate(agentId, date).orElseGet(() -> new AgentDay(agentId, date));
    }

    private boolean first(String kind, UUID id) {
        String key = kind + ":" + id;
        if (seen.existsById(key)) return false;
        seen.save(new EventSeen(key));
        return true;
    }
}
