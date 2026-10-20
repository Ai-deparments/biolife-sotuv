package uz.biolife.reporting.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.reporting.domain.ReportDelivery;

public interface ReportDeliveryRepository extends JpaRepository<ReportDelivery, UUID> {
    List<ReportDelivery> findTop50ByOrderBySentAtDesc();
}
