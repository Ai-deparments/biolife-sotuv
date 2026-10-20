package uz.biolife.rating.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.biolife.rating.application.RatingDtos.AgentCard;
import uz.biolife.rating.application.RatingDtos.Attention;
import uz.biolife.rating.application.RatingDtos.AttentionItem;
import uz.biolife.rating.application.RatingDtos.BestCaseDto;
import uz.biolife.rating.application.RatingDtos.CriterionPoints;
import uz.biolife.rating.application.RatingDtos.LeaderboardRow;
import uz.biolife.rating.application.RatingDtos.MeRating;
import uz.biolife.rating.application.RatingDtos.MissionDto;
import uz.biolife.rating.domain.AgentRef;
import uz.biolife.rating.domain.BestCase;
import uz.biolife.rating.domain.BonusRules;
import uz.biolife.rating.domain.Criteria;
import uz.biolife.rating.domain.VisitFact;
import uz.biolife.rating.domain.WeeklyRating;
import uz.biolife.rating.infra.RatingAgentRepository;
import uz.biolife.rating.infra.BestCaseRepository;
import uz.biolife.rating.infra.MissionRepository;
import uz.biolife.rating.infra.VisitFactRepository;
import uz.biolife.rating.infra.WeeklyRatingRepository;
import uz.biolife.shared.time.WorkHours;
import uz.biolife.shared.web.NotFoundException;

/** Read side of the rating module. Recomputes the week lazily when stale. */
@Service
public class RatingQueries {
    static final int TOP_N = 10;
    static final int FALLING_DAYS = 3;

    private final RatingAgentRepository agents;
    private final WeeklyRatingRepository ratings;
    private final VisitFactRepository facts;
    private final BestCaseRepository bestCases;
    private final MissionRepository missions;
    private final WeeklyRatingService weekly;
    private final MissionService missionService;
    private final Clock clock;

    public RatingQueries(RatingAgentRepository agents, WeeklyRatingRepository ratings, VisitFactRepository facts, BestCaseRepository bestCases,
                         MissionRepository missions, WeeklyRatingService weekly, MissionService missionService, Clock clock) {
        this.agents = agents; this.ratings = ratings; this.facts = facts; this.bestCases = bestCases; this.missions = missions;
        this.weekly = weekly; this.missionService = missionService; this.clock = clock;
    }

    public LocalDate resolveWeek(LocalDate week) {
        return WorkHours.weekStart(week == null ? LocalDate.now(clock) : week);
    }

    @Transactional
    public MeRating me(UUID agentId, LocalDate weekParam) {
        final LocalDate week = resolveWeek(weekParam);
        weekly.computeIfStale(week);
        AgentRef agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent topilmadi"));
        List<WeeklyRating> all = ratings.findByWeekStartOrderByRankAsc(week);
        WeeklyRating r = all.stream().filter(x -> x.getAgentId().equals(agentId)).findFirst()
            .orElseGet(() -> new WeeklyRating(agentId, week));
        List<VisitFact> fs = facts.findByAgentIdAndDateBetween(agentId, week, week.plusDays(6));
        Map<String, Integer> avg = missionService.averageCriteria(fs);
        List<CriterionPoints> criteria = Criteria.all().values().stream()
            .map(d -> new CriterionPoints(d.code(), d.title(), avg.getOrDefault(d.code(), 0), d.weight())).toList();
        MissionDto mission = missions.findByAgentIdAndWeekStart(agentId, week)
            .map(m -> new MissionDto(m.getCriterionCode(), m.getCurrent(), m.getTarget(), m.getText())).orElse(null);
        return new MeRating(agentId, agent.getName(), week, r.getAvgScore(), r.getRank(), all.size(), r.getTrend(), r.getStreakDays(),
            r.getVisits(), r.getClosed(), r.getConversations(), r.getProofsRate(), r.getPromoRate(),
            r.getSalesBonusUzs(), r.getConversationBonusUzs(), r.getBonusReason(),
            criteria, mission, leaderboard(week, null).stream().limit(TOP_N).toList(),
            bestCases.findByAgentIdAndPublishedTrueOrderByPublishedAtDesc(agentId).stream().map(this::toDto).toList());
    }

    @Transactional
    public List<LeaderboardRow> leaderboard(LocalDate week, String region) {
        week = resolveWeek(week);
        weekly.computeIfStale(week);
        Map<UUID, AgentRef> names = agentMap();
        List<WeeklyRating> rows = region == null || region.isBlank()
            ? ratings.findByWeekStartOrderByRankAsc(week) : ratings.findByWeekAndRegion(week, region);
        var published = bestCases.findByPublishedTrueOrderByPublishedAtDesc().stream().map(BestCase::getAgentId).collect(Collectors.toSet());
        List<LeaderboardRow> out = new ArrayList<>();
        for (WeeklyRating r : rows) {
            AgentRef a = names.get(r.getAgentId());
            if (a == null) continue;
            out.add(new LeaderboardRow(r.getRank(), a.getId(), a.getName(), a.getRegion(), r.getAvgScore(), r.getTrend(), r.getStreakDays(), published.contains(a.getId())));
        }
        return out;
    }

    /** "Bosganda: nega yuqori" — only rank, score, trend, streak and published best cases. */
    @Transactional
    public AgentCard card(UUID agentId, LocalDate weekParam) {
        final LocalDate week = resolveWeek(weekParam);
        weekly.computeIfStale(week);
        AgentRef a = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent topilmadi"));
        WeeklyRating r = ratings.findByAgentIdAndWeekStart(agentId, week).orElseGet(() -> new WeeklyRating(agentId, week));
        return new AgentCard(agentId, a.getName(), a.getRegion(), week, r.getRank(), r.getAvgScore(), r.getTrend(), r.getStreakDays(),
            bestCases.findByAgentIdAndPublishedTrueOrderByPublishedAtDesc(agentId).stream().map(this::toDto).toList());
    }

    /** Manager list: whom to encourage, who is falling. */
    @Transactional
    public Attention attention(LocalDate week) {
        week = resolveWeek(week);
        weekly.computeIfStale(week);
        BonusRules rules = weekly.rules();
        Map<UUID, AgentRef> names = agentMap();
        List<AttentionItem> encourage = new ArrayList<>();
        List<AttentionItem> falling = new ArrayList<>();
        for (WeeklyRating r : ratings.findByWeekStartOrderByRankAsc(week)) {
            AgentRef a = names.get(r.getAgentId());
            if (a == null) continue;
            if (r.getStreakDays() >= 5 || (r.getConversations() > 0 && r.getAvgScore() >= 90)) {
                encourage.add(item(a, r, r.getStreakDays() >= 5 ? r.getStreakDays() + " kun " + rules.getStreakThreshold() + "+" : r.getAvgScore() + " ball, zo'r keysga nomzod"));
            } else if (r.getConversations() > 0 && (r.getAvgScore() < rules.getFallingBelow() || r.getTrend() <= -5 || lowDays(r.getAgentId(), rules.getFallingBelow()) >= FALLING_DAYS)) {
                falling.add(item(a, r, r.getAvgScore() < rules.getFallingBelow() ? "o'rtacha " + r.getAvgScore() + " < " + rules.getFallingBelow()
                    : r.getTrend() <= -5 ? "o'tgan haftadan " + Math.abs(r.getTrend()) + " ball pastga" : FALLING_DAYS + " kundan beri " + rules.getFallingBelow() + " dan past"));
            }
        }
        return new Attention(week, encourage, falling);
    }

    @Transactional
    public List<BestCaseDto> bestCases(boolean published) {
        Map<UUID, AgentRef> names = agentMap();
        var list = published ? bestCases.findByPublishedTrueOrderByPublishedAtDesc() : bestCases.findByPublishedFalseOrderByScoreDesc();
        return list.stream().map(b -> toDto(b, names.get(b.getAgentId()))).toList();
    }

    @Transactional
    public BestCaseDto publish(UUID id, UUID managerId) {
        BestCase b = bestCases.findById(id).orElseThrow(() -> new NotFoundException("Keys topilmadi"));
        if (!b.isPublished()) b.publish(managerId, clock.instant());
        return toDto(b);
    }

    private int lowDays(UUID agentId, int below) {
        LocalDate today = LocalDate.now(clock);
        Map<LocalDate, List<Integer>> perDay = facts.findByAgentIdAndDateBetween(agentId, today.minusDays(7), today).stream()
            .filter(f -> f.getScore() != null)
            .collect(Collectors.groupingBy(VisitFact::getDate, Collectors.mapping(VisitFact::getScore, Collectors.toList())));
        int days = 0;
        LocalDate d = today;
        while (perDay.containsKey(d) || !WorkHours.isWorkDay(d)) {
            if (perDay.containsKey(d)) {
                if (perDay.get(d).stream().mapToInt(Integer::intValue).average().orElse(100) >= below) break;
                days++;
            }
            d = d.minusDays(1);
        }
        return days;
    }

    private Map<UUID, AgentRef> agentMap() {
        return agents.findAll().stream().collect(Collectors.toMap(AgentRef::getId, Function.identity()));
    }
    private AttentionItem item(AgentRef a, WeeklyRating r, String reason) {
        return new AttentionItem(a.getId(), a.getName(), a.getRegion(), r.getAvgScore(), r.getTrend(), r.getStreakDays(), reason);
    }
    private BestCaseDto toDto(BestCase b) { return toDto(b, agents.findById(b.getAgentId()).orElse(null)); }
    private BestCaseDto toDto(BestCase b, AgentRef a) {
        return new BestCaseDto(b.getId(), b.getAgentId(), a == null ? null : a.getName(), b.getScore(), b.getSummary(), b.isPublished(), b.getPublishedAt());
    }
}
