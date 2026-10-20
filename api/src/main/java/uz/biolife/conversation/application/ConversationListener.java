package uz.biolife.conversation.application;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import uz.biolife.conversation.domain.ConvAgent;
import uz.biolife.conversation.domain.ConvVisit;
import uz.biolife.conversation.infra.ConvAgentRepository;
import uz.biolife.conversation.infra.ConvVisitRepository;
import uz.biolife.shared.events.DomainEvents.AgentProfileChanged;
import uz.biolife.shared.events.DomainEvents.VisitRecorded;
import uz.biolife.shared.persistence.AggregateLock;

/** Keeps the module's own copies: Linko visits (to place a recording in a shop) and agent names. Upserts only. */
@Component
public class ConversationListener {
    private final ConvVisitRepository visits;
    private final ConvAgentRepository agents;
    private final AggregateLock lock;

    public ConversationListener(ConvVisitRepository visits, ConvAgentRepository agents, AggregateLock lock) { this.visits = visits; this.agents = agents; this.lock = lock; }

    @ApplicationModuleListener
    public void on(VisitRecorded e) {
        lock.acquire("conv:visit:" + e.visitId());
        ConvVisit v = visits.findById(e.visitId()).orElseGet(() -> new ConvVisit(e.visitId()));
        v.update(e.agentId(), e.date(), e.startedAt(), e.endedAt(), e.outletName());
        visits.save(v);
    }

    @ApplicationModuleListener
    public void on(AgentProfileChanged e) {
        lock.acquire("conv:agent:" + e.agentId());
        ConvAgent a = agents.findById(e.agentId()).orElseGet(() -> new ConvAgent(e.agentId()));
        a.update(e.name(), e.region(), e.active());
        agents.save(a);
    }
}
