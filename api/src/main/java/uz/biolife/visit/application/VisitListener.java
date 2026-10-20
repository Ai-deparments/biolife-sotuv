package uz.biolife.visit.application;

import java.time.Clock;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import uz.biolife.shared.events.DomainEvents.AgentProfileChanged;
import uz.biolife.shared.events.DomainEvents.DayPlanned;
import uz.biolife.shared.events.DomainEvents.VisitRecorded;
import uz.biolife.shared.persistence.AggregateLock;
import uz.biolife.visit.domain.PlanDay;
import uz.biolife.visit.domain.VisitAgent;
import uz.biolife.visit.domain.VisitRecord;
import uz.biolife.visit.infra.PlanDayRepository;
import uz.biolife.visit.infra.VisitAgentRepository;
import uz.biolife.visit.infra.VisitRecordRepository;

/** Keeps the visit list current. Every handler is an upsert, so re-delivery is harmless. */
@Component
public class VisitListener {
    private final VisitRecordRepository visits;
    private final VisitAgentRepository agents;
    private final PlanDayRepository plans;
    private final AggregateLock lock;
    private final Clock clock;

    public VisitListener(VisitRecordRepository visits, VisitAgentRepository agents, PlanDayRepository plans, AggregateLock lock, Clock clock) {
        this.visits = visits; this.agents = agents; this.plans = plans; this.lock = lock; this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(VisitRecorded e) {
        lock.acquire("visit:" + e.visitId());
        VisitRecord r = visits.findById(e.visitId()).orElseGet(() -> new VisitRecord(e.visitId()));
        if (r.apply(e, clock.instant())) visits.save(r);
    }

    @ApplicationModuleListener
    public void on(AgentProfileChanged e) {
        lock.acquire("visit:agent:" + e.agentId());
        VisitAgent a = agents.findById(e.agentId()).orElseGet(() -> new VisitAgent(e.agentId()));
        a.update(e.name(), e.region(), e.active());
        agents.save(a);
    }

    @ApplicationModuleListener
    public void on(DayPlanned e) {
        lock.acquire("visit:plan:" + e.agentId() + ":" + e.date());
        PlanDay p = plans.findById(PlanDay.idOf(e.agentId(), e.date())).orElseGet(() -> new PlanDay(e.agentId(), e.date()));
        p.setPlanned(e.plannedVisits());
        plans.save(p);
    }
}
