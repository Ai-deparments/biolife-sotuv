package uz.biolife.rating.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-row configuration of the two kinds of money.
 * Sales bonus = sales × salesPercent. Conversation bonus is paid when avg score, proofs rate and
 * absence of unresolved flags all pass. Streak threshold defines a "good day".
 */
@Entity
@Table(name = "rating_rules")
public class BonusRules {
    public static final int SINGLETON_ID = 1;

    @Id private int id = SINGLETON_ID;
    private int salesPercentBp;          // basis points: 150 = 1.5 %
    private int conversationMinScore;    // e.g. 80
    private int conversationMinProofsRate; // e.g. 90 (%)
    private long conversationBonusUzs;   // e.g. 380 000
    private int streakThreshold;         // e.g. 80 — daily avg score counted as a good day
    private int fallingBelow;            // e.g. 50 — manager attention threshold

    protected BonusRules() {}
    public static BonusRules defaults() {
        BonusRules r = new BonusRules();
        r.salesPercentBp = 150; r.conversationMinScore = 80; r.conversationMinProofsRate = 90;
        r.conversationBonusUzs = 380_000; r.streakThreshold = 80; r.fallingBelow = 50;
        return r;
    }
    public void update(int salesPercentBp, int conversationMinScore, int conversationMinProofsRate, long conversationBonusUzs, int streakThreshold, int fallingBelow) {
        if (salesPercentBp < 0 || salesPercentBp > 5000) throw new IllegalArgumentException("salesPercentBp 0–5000");
        if (conversationMinScore < 0 || conversationMinScore > 100) throw new IllegalArgumentException("conversationMinScore 0–100");
        if (conversationMinProofsRate < 0 || conversationMinProofsRate > 100) throw new IllegalArgumentException("conversationMinProofsRate 0–100");
        if (conversationBonusUzs < 0) throw new IllegalArgumentException("conversationBonusUzs >= 0");
        this.salesPercentBp = salesPercentBp; this.conversationMinScore = conversationMinScore;
        this.conversationMinProofsRate = conversationMinProofsRate; this.conversationBonusUzs = conversationBonusUzs;
        this.streakThreshold = streakThreshold; this.fallingBelow = fallingBelow;
    }
    public long salesBonusFor(long salesUzs) { return salesUzs * salesPercentBp / 10_000; }

    public int getSalesPercentBp() { return salesPercentBp; }
    public int getConversationMinScore() { return conversationMinScore; }
    public int getConversationMinProofsRate() { return conversationMinProofsRate; }
    public long getConversationBonusUzs() { return conversationBonusUzs; }
    public int getStreakThreshold() { return streakThreshold; }
    public int getFallingBelow() { return fallingBelow; }
}
