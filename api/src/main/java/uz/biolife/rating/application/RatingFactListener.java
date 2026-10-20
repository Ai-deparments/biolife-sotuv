package uz.biolife.rating.application;

import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import uz.biolife.rating.domain.AgentRef;
import uz.biolife.rating.domain.BestCase;
import uz.biolife.rating.domain.VisitFact;
import uz.biolife.rating.infra.RatingAgentRepository;
import uz.biolife.rating.infra.BestCaseRepository;
import uz.biolife.rating.infra.VisitFactRepository;
import uz.biolife.shared.events.DomainEvents.AgentProfileChanged;
import uz.biolife.shared.events.DomainEvents.AnalysisCompleted;
import uz.biolife.shared.events.DomainEvents.OrderConfirmed;
import uz.biolife.shared.events.DomainEvents.VisitClosed;
import uz.biolife.shared.persistence.AggregateLock;
import uz.biolife.shared.time.WorkHours;

/**
 * Turns domain events into rating facts. Every handler is idempotent (keyed by visit / analysis id),
 * so Modulith may re-deliver after a restart without double counting.
 */
@Component
public class RatingFactListener {
    private static final Logger log = LoggerFactory.getLogger(RatingFactListener.class);
    /** Conversations at or above this score become best-case candidates for the manager. */
    static final int BEST_CASE_MIN_SCORE = 90;

    private final RatingAgentRepository agents;
    private final VisitFactRepository facts;
    private final BestCaseRepository bestCases;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AggregateLock lock;

    public RatingFactListener(RatingAgentRepository agents, VisitFactRepository facts, BestCaseRepository bestCases, ObjectMapper mapper, Clock clock, AggregateLock lock) {
        this.agents = agents; this.facts = facts; this.bestCases = bestCases; this.mapper = mapper; this.clock = clock; this.lock = lock;
    }

    @ApplicationModuleListener
    public void on(AgentProfileChanged e) {
        lock.acquire("rating:agent:" + e.agentId());
        agents.findById(e.agentId()).ifPresentOrElse(
            a -> a.update(e.name(), e.region(), e.district(), e.active()),
            () -> agents.save(new AgentRef(e.agentId(), e.name(), e.region(), e.district(), e.active())));
    }

    @ApplicationModuleListener
    public void on(VisitClosed e) {
        lock.acquire("rating:visit:" + e.visitId());
        VisitFact f = facts.findById(e.visitId()).orElseGet(() -> new VisitFact(e.visitId(), e.agentId(), e.date()));
        f.applyClosed(e.proofsComplete(), e.excused(), e.suspect(), e.blocks());
        facts.save(f);
    }

    @ApplicationModuleListener
    public void on(AnalysisCompleted e) {
        lock.acquire("rating:agent:" + e.agentId());
        lock.acquire("rating:visit:" + e.visitId());
        VisitFact f = facts.findById(e.visitId()).orElseGet(() -> new VisitFact(e.visitId(), e.agentId(), e.date()));
        f.applyAnalysis(e.score(), e.promoMentioned(), e.flags() != null && !e.flags().isEmpty(), toJson(e));
        facts.save(f);
        if (e.score() >= BEST_CASE_MIN_SCORE && (e.flags() == null || e.flags().isEmpty())
            && bestCases.findByAnalysisId(e.analysisId()).isEmpty()) {
            // one pending candidate per agent per week: keep the best, so the manager's list stays short
            LocalDate ws = WorkHours.weekStart(e.date());
            var existing = bestCases.findFirstByAgentIdAndPublishedFalseAndVisitDateBetween(e.agentId(), ws, ws.plusDays(6));
            if (existing.isPresent() && existing.get().getScore() >= e.score()) return;
            existing.ifPresent(bestCases::delete);
            bestCases.save(new BestCase(e.analysisId(), e.visitId(), e.agentId(), e.date(), e.score(), e.summary(), clock.instant()));
            log.info("best case candidate agent={} score={}", e.agentId(), e.score());
        }
    }

    @ApplicationModuleListener
    public void on(OrderConfirmed e) {
        lock.acquire("rating:visit:" + e.visitId());
        VisitFact f = facts.findById(e.visitId()).orElseGet(() -> new VisitFact(e.visitId(), e.agentId(), e.date()));
        f.applyOrder(e.amountUzs(), e.blocks());
        facts.save(f);
    }

    private String toJson(AnalysisCompleted e) {
        return mapper.writeValueAsString(e.criteria() == null ? java.util.Map.of() : e.criteria());
    }
}
