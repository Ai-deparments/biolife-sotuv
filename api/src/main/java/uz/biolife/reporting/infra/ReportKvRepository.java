package uz.biolife.reporting.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.reporting.domain.ReportKv;

public interface ReportKvRepository extends JpaRepository<ReportKv, String> {}
