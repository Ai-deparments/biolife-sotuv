package uz.biolife.reporting.infra;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.reporting.domain.AgentDay;
import uz.biolife.reporting.domain.AgentRef;
import uz.biolife.reporting.domain.EventSeen;
import uz.biolife.reporting.domain.ReportDelivery;

public interface AgentDayRepository extends JpaRepository<AgentDay, UUID> {
    Optional<AgentDay> findByAgentIdAndDate(UUID agentId, LocalDate date);
    List<AgentDay> findByDate(LocalDate date);
    List<AgentDay> findByDateBetween(LocalDate from, LocalDate to);
    List<AgentDay> findByAgentIdAndDateBetweenOrderByDateAsc(UUID agentId, LocalDate from, LocalDate to);
}
