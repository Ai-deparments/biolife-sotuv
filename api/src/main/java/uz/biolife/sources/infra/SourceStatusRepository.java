package uz.biolife.sources.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.sources.domain.SourceStatus;

public interface SourceStatusRepository extends JpaRepository<SourceStatus, String> {}
