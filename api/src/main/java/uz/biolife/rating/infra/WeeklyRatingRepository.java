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

public interface WeeklyRatingRepository extends JpaRepository<WeeklyRating, UUID> {
    Optional<WeeklyRating> findByAgentIdAndWeekStart(UUID agentId, LocalDate weekStart);
    List<WeeklyRating> findByWeekStartOrderByRankAsc(LocalDate weekStart);
    @Query("select r from WeeklyRating r join RatingAgent a on a.id = r.agentId where r.weekStart = :week and a.region = :region order by r.rank")
    List<WeeklyRating> findByWeekAndRegion(LocalDate week, String region);
}
