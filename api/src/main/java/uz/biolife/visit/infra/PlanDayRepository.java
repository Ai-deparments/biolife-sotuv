package uz.biolife.visit.infra;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.visit.domain.PlanDay;

public interface PlanDayRepository extends JpaRepository<PlanDay, UUID> {}
