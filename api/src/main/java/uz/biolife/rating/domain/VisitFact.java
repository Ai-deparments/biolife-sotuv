package uz.biolife.rating.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row per visit, built up from several events (closed → analysed → ordered).
 * Keyed by visit id so re-delivered events are idempotent.
 */
@Entity
@Table(name = "rating_visit_fact")
public class VisitFact {
    @Id private UUID visitId;
    private UUID agentId;
    private LocalDate date;
    private boolean closed;
    private boolean proofsComplete;
    private boolean excused;
    private boolean suspect;
    private int blocks;
    private Integer score;
    private Boolean promoMentioned;
    private boolean flagged;
    @Column(columnDefinition = "text") private String criteriaJson;
    private long orderAmountUzs;

    protected VisitFact() {}
    public VisitFact(UUID visitId, UUID agentId, LocalDate date) { this.visitId = visitId; this.agentId = agentId; this.date = date; }

    public void applyClosed(boolean proofsComplete, boolean excused, boolean suspect, int blocks) {
        this.closed = true; this.proofsComplete = proofsComplete; this.excused = excused; this.suspect = suspect; this.blocks = blocks;
    }
    public void applyAnalysis(int score, boolean promoMentioned, boolean flagged, String criteriaJson) {
        this.score = score; this.promoMentioned = promoMentioned; this.flagged = flagged; this.criteriaJson = criteriaJson;
    }
    public void applyOrder(long amountUzs, int blocks) { this.orderAmountUzs = amountUzs; if (blocks > 0) this.blocks = blocks; }

    public UUID getVisitId() { return visitId; }
    public UUID getAgentId() { return agentId; }
    public LocalDate getDate() { return date; }
    public boolean isClosed() { return closed; }
    public boolean isProofsComplete() { return proofsComplete; }
    public boolean isExcused() { return excused; }
    public boolean isSuspect() { return suspect; }
    public int getBlocks() { return blocks; }
    public Integer getScore() { return score; }
    public Boolean getPromoMentioned() { return promoMentioned; }
    public boolean isFlagged() { return flagged; }
    public String getCriteriaJson() { return criteriaJson; }
    public long getOrderAmountUzs() { return orderAmountUzs; }
}
