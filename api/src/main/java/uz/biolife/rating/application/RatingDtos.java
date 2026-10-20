package uz.biolife.rating.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API shapes of the rating module. Shared with web via OpenAPI. */
public final class RatingDtos {
    private RatingDtos() {}

    public record CriterionPoints(String code, String title, int points, int weight) {}
    public record MissionDto(String criterionCode, int current, int target, String text) {}
    public record BestCaseDto(UUID id, UUID agentId, String agentName, int score, String summary, boolean published, Instant publishedAt) {}

    /** Full view for the agent themself. */
    public record MeRating(UUID agentId, String name, LocalDate weekStart, int score, int rank, int totalAgents, int trend, int streakDays,
                           int visits, int closed, int conversations, int proofsRate, int promoRate,
                           long salesBonusUzs, long conversationBonusUzs, String bonusReason,
                           List<CriterionPoints> criteria, MissionDto mission, List<LeaderboardRow> top, List<BestCaseDto> myBestCases) {}

    public record LeaderboardRow(int rank, UUID agentId, String name, String region, int score, int trend, int streakDays, boolean hasBestCase) {}

    /** Public card: why they are high, only published best cases, no money, no breakdown. */
    public record AgentCard(UUID agentId, String name, String region, LocalDate weekStart, int rank, int score, int trend, int streakDays,
                            List<BestCaseDto> bestCases) {}

    public record AttentionItem(UUID agentId, String name, String region, int score, int trend, int streakDays, String reason) {}
    public record Attention(LocalDate weekStart, List<AttentionItem> encourage, List<AttentionItem> falling) {}

    public record RulesDto(int salesPercentBp, int conversationMinScore, int conversationMinProofsRate, long conversationBonusUzs,
                           int streakThreshold, int fallingBelow) {}
}
