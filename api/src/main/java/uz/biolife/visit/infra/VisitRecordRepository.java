package uz.biolife.visit.infra;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.visit.domain.VisitRecord;

public interface VisitRecordRepository extends JpaRepository<VisitRecord, UUID> {}
