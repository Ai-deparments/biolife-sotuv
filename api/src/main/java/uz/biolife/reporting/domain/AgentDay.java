package uz.biolife.reporting.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import uz.biolife.shared.time.WorkHours;

/** One row per agent per day: every KPI the panel shows, kept current by events. */
@Entity
@Table(name = "report_agent_day")
public class AgentDay {
    @Id private UUID id;
    private UUID agentId;
    private LocalDate date;
    private int planned;
    private int started;
    private int closed;
    private int proofsComplete;
    private int excused;
    private int suspect;
    private int scoreSum;
    private int scoreCount;
    private int promoCount;
    private int photosTotal;
    private int photosOk;
    private int blocks;
    private long salesUzs;
    private Instant firstActivity;
    private Instant lastActivity;
    private int activeMinutes;
    private boolean lateStart;
    private boolean earlyFinish;

    protected AgentDay() {}
    public AgentDay(UUID agentId, LocalDate date) { this.id = UUID.randomUUID(); this.agentId = agentId; this.date = date; }

    public void setPlanned(int planned) { this.planned = planned; }
    public void addStarted() { started++; }
    public void addClosed(boolean proofs, boolean excused, boolean suspect, int blocks) {
        if (excused) this.excused++; else { closed++; if (proofs) proofsComplete++; }
        if (suspect) this.suspect++;
        this.blocks += blocks;
    }
    public void addScore(int score, boolean promo) { scoreSum += score; scoreCount++; if (promo) promoCount++; }
    public void addPhoto(boolean ok) { photosTotal++; if (ok) photosOk++; }
    public void addSales(long uzs) { salesUzs += uzs; }

    /** Records an activity timestamp and re-derives the working-hours figures. */
    public void touch(Instant at) {
        if (firstActivity == null || at.isBefore(firstActivity)) firstActivity = at;
        if (lastActivity == null || at.isAfter(lastActivity)) lastActivity = at;
        activeMinutes = WorkHours.overlapMinutes(date, firstActivity, lastActivity);
        lateStart = WorkHours.isLateStart(firstActivity);
        earlyFinish = WorkHours.isEarlyFinish(lastActivity);
    }

    public int avgScore() { return scoreCount == 0 ? 0 : Math.round((float) scoreSum / scoreCount); }
    public int promoRate() { return scoreCount == 0 ? 0 : Math.round(100f * promoCount / scoreCount); }

    public UUID getId() { return id; }
    public UUID getAgentId() { return agentId; }
    public LocalDate getDate() { return date; }
    public int getPlanned() { return planned; }
    public int getStarted() { return started; }
    public int getClosed() { return closed; }
    public int getProofsComplete() { return proofsComplete; }
    public int getExcused() { return excused; }
    public int getSuspect() { return suspect; }
    public int getScoreSum() { return scoreSum; }
    public int getScoreCount() { return scoreCount; }
    public int getPromoCount() { return promoCount; }
    public int getPhotosTotal() { return photosTotal; }
    public int getPhotosOk() { return photosOk; }
    public int getBlocks() { return blocks; }
    public long getSalesUzs() { return salesUzs; }
    public Instant getFirstActivity() { return firstActivity; }
    public Instant getLastActivity() { return lastActivity; }
    public int getActiveMinutes() { return activeMinutes; }
    public boolean isLateStart() { return lateStart; }
    public boolean isEarlyFinish() { return earlyFinish; }
}
