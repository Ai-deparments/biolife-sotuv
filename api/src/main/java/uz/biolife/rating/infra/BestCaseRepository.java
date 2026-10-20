package uz.biolife.rating.infra;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.biolife.rating.domain.AgentRef;
import uz.biolife.rating.domain.BestCase;
import uz.biolife.rating.domain.BonusRules;
import uz.biolife.rating.domain.Mission;
import uz.biolife.rating.domain.VisitFact;
import uz.biolife.rating.domain.WeeklyRating;

public interface BestCaseRepository extends JpaRepository<BestCase, UUID> {
    Optional<BestCase> findByAnalysisId(UUID analysisId);
    Optional<BestCase> findFirstByAgentIdAndPublishedFalseAndVisitDateBetween(UUID agentId, LocalDate from, LocalDate to);
    List<BestCase> findByAgentIdAndPublishedTrueOrderByPublishedAtDesc(UUID agentId);
    List<BestCase> findByPublishedTrueOrderByPublishedAtDesc();
    List<BestCase> findByPublishedFalseOrderByScoreDesc();
}
