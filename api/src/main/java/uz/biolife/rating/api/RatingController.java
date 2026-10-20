package uz.biolife.rating.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.rating.application.RatingDtos.AgentCard;
import uz.biolife.rating.application.RatingDtos.LeaderboardRow;
import uz.biolife.rating.application.RatingDtos.MeRating;
import uz.biolife.rating.application.RatingQueries;
import uz.biolife.shared.security.CurrentUser;

/** Agent-facing rating endpoints. Everyone sees the leaderboard; details only for self. */
@RestController
@RequestMapping("/api/v1")
public class RatingController {
    private final RatingQueries queries;

    public RatingController(RatingQueries queries) { this.queries = queries; }

    @GetMapping("/me/rating")
    public MeRating me(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
        return queries.me(user.id(), week);
    }

    @GetMapping("/rating/leaderboard")
    public List<LeaderboardRow> leaderboard(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                                            @RequestParam(required = false) String region) {
        return queries.leaderboard(week, region);
    }

    @GetMapping("/rating/agents/{agentId}")
    public AgentCard card(CurrentUser user, @PathVariable UUID agentId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
        return queries.card(agentId, week);
    }
}
