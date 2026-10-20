package uz.biolife.visit.infra;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.visit.domain.VisitAgent;

public interface VisitAgentRepository extends JpaRepository<VisitAgent, UUID> {}
