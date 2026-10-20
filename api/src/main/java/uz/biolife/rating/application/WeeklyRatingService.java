package uz.biolife.rating.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.biolife.rating.domain.AgentRef;
import uz.biolife.rating.domain.BonusRules;
import uz.biolife.rating.domain.VisitFact;
import uz.biolife.rating.domain.WeeklyRating;
import uz.biolife.rating.infra.RatingAgentRepository;
import uz.biolife.rating.infra.BonusRulesRepository;
import uz.biolife.rating.infra.VisitFactRepository;
import uz.biolife.rating.infra.WeeklyRatingRepository;
import uz.biolife.shared.time.WorkHours;

/**
 * Computes the weekly standing of every active agent: metrics, rank, trend, streak, two bonuses, mission.
 * Idempotent per week; cheap enough (140 agents) to run on read when stale.
 */
@Service
public class WeeklyRatingService {
    private static final Logger log = LoggerFactory.getLogger(WeeklyRatingService.class);
    static final Duration STALE_AFTER = Duration.ofMinutes(5);
    static final int STREAK_LOOKBACK_DAYS = 30;

    private final RatingAgentRepository agents;
    private final VisitFactRepository facts;
    private final WeeklyRatingRepository ratings;
    private final BonusRulesRepository rulesRepo;
    private final MissionService missions;
    private final Clock clock;

    public WeeklyRatingService(RatingAgentRepository agents, VisitFactRepository facts, WeeklyRatingRepository ratings,
                               BonusRulesRepository rulesRepo, MissionService missions, Clock clock) {
        this.agents = agents; this.facts = facts; this.ratings = ratings; this.rulesRepo = rulesRepo; this.missions = missions; this.clock = clock;
    }

    @Transactional
    public BonusRules rules() {
        return rulesRepo.findById(BonusRules.SINGLETON_ID).orElseGet(() -> rulesRepo.save(BonusRules.defaults()));
    }

    /** Recompute only if nothing was computed for the week in the last {@link #STALE_AFTER}. */
    @Transactional
    public void computeIfStale(LocalDate weekStart) {
        var rows = ratings.findByWeekStartOrderByRankAsc(weekStart);
        boolean fresh = !rows.isEmpty() && rows.get(0).getComputedAt() != null
            && rows.get(0).getComputedAt().isAfter(clock.instant().minus(STALE_AFTER));
        if (!fresh) compute(weekStart);
    }

    @Transactional
    public List<WeeklyRating> compute(LocalDate week) {
        final LocalDate weekStart = WorkHours.weekStart(week);
        LocalDate weekEnd = weekStart.plusDays(6);
        BonusRules rules = rules();
        List<AgentRef> active = agents.findByActiveTrue();
        Map<UUID, List<VisitFact>> byAgent = facts.findByDateBetween(weekStart, weekEnd).stream()
            .collect(Collectors.groupingBy(VisitFact::getAgentId));
        Map<UUID, Integer> previous = ratings.findByWeekStartOrderByRankAsc(weekStart.minusWeeks(1)).stream()
            .collect(Collectors.toMap(WeeklyRating::getAgentId, WeeklyRating::getAvgScore, (a, b) -> a));

        List<WeeklyRating> rows = new ArrayList<>();
        for (AgentRef agent : active) {
            List<VisitFact> fs = byAgent.getOrDefault(agent.getId(), List.of());
            WeeklyRating r = ratings.findByAgentIdAndWeekStart(agent.getId(), weekStart)
                .orElseGet(() -> new WeeklyRating(agent.getId(), weekStart));
            Metrics m = Metrics.of(fs);
            r.setMetrics(m.visits, m.closed, m.conversations, m.avgScore, m.proofsRate, m.promoRate, m.salesUzs);
            int streak = streakDays(agent.getId(), weekEnd, rules.getStreakThreshold());
            int trend = previous.containsKey(agent.getId()) ? m.avgScore - previous.get(agent.getId()) : 0;
            r.setStanding(0, trend, streak);
            applyBonus(r, m, rules, fs);
            missions.refresh(agent.getId(), weekStart, missions.averageCriteria(fs));
            rows.add(r);
        }
        rows.sort(Comparator.comparingInt(WeeklyRating::getAvgScore).reversed()
            .thenComparing(Comparator.comparingInt(WeeklyRating::getProofsRate).reversed())
            .thenComparing(Comparator.comparingInt(WeeklyRating::getClosed).reversed()));
        int rank = 0;
        for (WeeklyRating r : rows) { r.setStanding(++rank, r.getTrend(), r.getStreakDays()); r.touch(clock.instant()); }
        ratings.saveAll(rows);
        log.info("weekly rating computed week={} agents={}", weekStart, rows.size());
        return rows;
    }

    private void applyBonus(WeeklyRating r, Metrics m, BonusRules rules, List<VisitFact> fs) {
        long sales = rules.salesBonusFor(m.salesUzs);
        boolean hasFlags = fs.stream().anyMatch(VisitFact::isFlagged);
        List<String> why = new ArrayList<>();
        if (m.conversations == 0) why.add("suhbat yo'q");
        if (m.avgScore < rules.getConversationMinScore()) why.add("o'rtacha ball " + m.avgScore + " < " + rules.getConversationMinScore());
        if (m.proofsRate < rules.getConversationMinProofsRate()) why.add("dalillar " + m.proofsRate + "% < " + rules.getConversationMinProofsRate() + "%");
        if (hasFlags) why.add("shubhali yozuv bor");
        boolean eligible = why.isEmpty();
        r.setBonus(sales, eligible ? rules.getConversationBonusUzs() : 0,
            eligible ? "Suhbat bonusi: shartlar bajarildi" : "Suhbat bonusi yo'q: " + String.join(", ", why));
    }

    /** Consecutive days (ending at the last day with data on or before weekEnd) with daily average ≥ threshold. */
    int streakDays(UUID agentId, LocalDate weekEnd, int threshold) {
        List<VisitFact> history = facts.findByAgentIdAndDateBetween(agentId, weekEnd.minusDays(STREAK_LOOKBACK_DAYS), weekEnd);
        Map<LocalDate, List<Integer>> perDay = new HashMap<>();
        for (VisitFact f : history) if (f.getScore() != null) perDay.computeIfAbsent(f.getDate(), d -> new ArrayList<>()).add(f.getScore());
        if (perDay.isEmpty()) return 0;
        LocalDate day = perDay.keySet().stream().max(Comparator.naturalOrder()).orElseThrow();
        int streak = 0;
        while (true) {
            List<Integer> scores = perDay.get(day);
            if (scores == null) { if (!WorkHours.isWorkDay(day)) { day = day.minusDays(1); continue; } break; }
            if (scores.stream().mapToInt(Integer::intValue).average().orElse(0) < threshold) break;
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    record Metrics(int visits, int closed, int conversations, int avgScore, int proofsRate, int promoRate, long salesUzs) {
        static Metrics of(List<VisitFact> fs) {
            int visits = fs.size();
            int closed = (int) fs.stream().filter(f -> f.isClosed() && !f.isExcused()).count();
            int proofs = (int) fs.stream().filter(f -> f.isClosed() && !f.isExcused() && f.isProofsComplete()).count();
            List<VisitFact> conv = fs.stream().filter(f -> f.getScore() != null).toList();
            int avg = (int) Math.round(conv.stream().mapToInt(VisitFact::getScore).average().orElse(0));
            int promo = (int) conv.stream().filter(f -> Boolean.TRUE.equals(f.getPromoMentioned())).count();
            long sales = fs.stream().mapToLong(VisitFact::getOrderAmountUzs).sum();
            return new Metrics(visits, closed, conv.size(), avg,
                closed == 0 ? 0 : Math.round(100f * proofs / closed),
                conv.isEmpty() ? 0 : Math.round(100f * promo / conv.size()), sales);
        }
    }
}
