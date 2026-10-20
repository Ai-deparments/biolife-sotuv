package uz.biolife.reporting.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.reporting.domain.EventSeen;

public interface EventSeenRepository extends JpaRepository<EventSeen, String> {}
