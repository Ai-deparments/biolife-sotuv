package uz.biolife.rating.api;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.rating.application.RatingDtos.Attention;
import uz.biolife.rating.application.RatingDtos.BestCaseDto;
import uz.biolife.rating.application.RatingDtos.RulesDto;
import uz.biolife.rating.application.RatingQueries;
import uz.biolife.rating.application.WeeklyRatingService;
import uz.biolife.rating.domain.BonusRules;
import uz.biolife.shared.security.CurrentUser;

/** Manager endpoints: attention list, bonus rules, best cases, manual recompute. */
@RestController
@RequestMapping("/api/v1")
public class PanelRatingController {
    private final RatingQueries queries;
    private final WeeklyRatingService weekly;

    public PanelRatingController(RatingQueries queries, WeeklyRatingService weekly) { this.queries = queries; this.weekly = weekly; }

    @GetMapping("/panel/rating/attention")
    public Attention attention(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
        user.requireManager();
        return queries.attention(week);
    }

    @GetMapping("/panel/rating/rules")
    public RulesDto rules(CurrentUser user) {
        user.requireManager();
        return toDto(weekly.rules());
    }

    @PutMapping("/panel/rating/rules")
    @Transactional
    public RulesDto updateRules(CurrentUser user, @RequestBody @Valid RulesDto dto) {
        user.requireManager();
        BonusRules r = weekly.rules();
        r.update(dto.salesPercentBp(), dto.conversationMinScore(), dto.conversationMinProofsRate(), dto.conversationBonusUzs(), dto.streakThreshold(), dto.fallingBelow());
        return toDto(r);
    }

    @GetMapping("/panel/best-cases")
    public List<BestCaseDto> bestCases(CurrentUser user, @RequestParam(defaultValue = "pending") String status) {
        user.requireManager();
        return queries.bestCases("published".equals(status));
    }

    @PostMapping("/panel/best-cases/{id}/publish")
    public BestCaseDto publish(CurrentUser user, @PathVariable UUID id) {
        user.requireManager();
        return queries.publish(id, user.id());
    }

    @PostMapping("/internal/rating/recompute")
    public int recompute(CurrentUser user, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
        user.requireManager();
        return weekly.compute(week).size();
    }

    private static RulesDto toDto(BonusRules r) {
        return new RulesDto(r.getSalesPercentBp(), r.getConversationMinScore(), r.getConversationMinProofsRate(), r.getConversationBonusUzs(), r.getStreakThreshold(), r.getFallingBelow());
    }
}
