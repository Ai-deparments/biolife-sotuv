package uz.biolife.rating.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Computed weekly standing of one agent. Recomputed idempotently per (agent, week). */
@Entity
@Table(name = "rating_weekly")
public class WeeklyRating {
    @Id private UUID id;
    private UUID agentId;
    private LocalDate weekStart;
    private int visits;
    private int closed;
    private int conversations;
    private int avgScore;
    private int proofsRate;
    private int promoRate;
    private long salesUzs;
    private int rank;
    private int trend;
    private int streakDays;
    private long salesBonusUzs;
    private long conversationBonusUzs;
    private String bonusReason;
    private Instant computedAt;

    protected WeeklyRating() {}
    public WeeklyRating(UUID agentId, LocalDate weekStart) { this.id = UUID.randomUUID(); this.agentId = agentId; this.weekStart = weekStart; }

    public void setMetrics(int visits, int closed, int conversations, int avgScore, int proofsRate, int promoRate, long salesUzs) {
        this.visits = visits; this.closed = closed; this.conversations = conversations; this.avgScore = avgScore;
        this.proofsRate = proofsRate; this.promoRate = promoRate; this.salesUzs = salesUzs;
    }
    public void setStanding(int rank, int trend, int streakDays) { this.rank = rank; this.trend = trend; this.streakDays = streakDays; }
    public void setBonus(long salesBonusUzs, long conversationBonusUzs, String reason) {
        this.salesBonusUzs = salesBonusUzs; this.conversationBonusUzs = conversationBonusUzs; this.bonusReason = reason;
    }
    public void touch(Instant now) { this.computedAt = now; }

    public UUID getId() { return id; }
    public UUID getAgentId() { return agentId; }
    public LocalDate getWeekStart() { return weekStart; }
    public int getVisits() { return visits; }
    public int getClosed() { return closed; }
    public int getConversations() { return conversations; }
    public int getAvgScore() { return avgScore; }
    public int getProofsRate() { return proofsRate; }
    public int getPromoRate() { return promoRate; }
    public long getSalesUzs() { return salesUzs; }
    public int getRank() { return rank; }
    public int getTrend() { return trend; }
    public int getStreakDays() { return streakDays; }
    public long getSalesBonusUzs() { return salesBonusUzs; }
    public long getConversationBonusUzs() { return conversationBonusUzs; }
    public String getBonusReason() { return bonusReason; }
    public Instant getComputedAt() { return computedAt; }
}
