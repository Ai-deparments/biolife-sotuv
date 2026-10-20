package uz.biolife.rating.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.biolife.rating.domain.BonusRules;

public interface BonusRulesRepository extends JpaRepository<BonusRules, Integer> {}
