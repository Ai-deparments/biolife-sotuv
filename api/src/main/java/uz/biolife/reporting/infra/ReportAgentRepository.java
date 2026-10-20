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

public interface ReportAgentRepository extends JpaRepository<AgentRef, UUID> {
    List<AgentRef> findByActiveTrue();
}
