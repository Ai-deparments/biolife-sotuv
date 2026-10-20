package uz.biolife.conversation.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.conversation.domain.ConvSettings;

public interface ConvSettingsRepository extends JpaRepository<ConvSettings, Integer> {}
