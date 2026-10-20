package uz.biolife.conversation.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.conversation.domain.ConvAgent;

public interface ConvAgentRepository extends JpaRepository<ConvAgent, java.util.UUID> {}
