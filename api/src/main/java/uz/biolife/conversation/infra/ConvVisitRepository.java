package uz.biolife.conversation.infra;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.conversation.domain.ConvVisit;

public interface ConvVisitRepository extends JpaRepository<ConvVisit, UUID> {
    List<ConvVisit> findByAgentIdAndDate(UUID agentId, LocalDate date);
}
