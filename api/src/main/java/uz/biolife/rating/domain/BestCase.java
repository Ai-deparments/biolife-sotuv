package uz.biolife.rating.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A conversation worth showing to everyone. Created automatically for high scores,
 * visible to other agents only after a manager publishes it ("faqat zo'r keyslar").
 */
@Entity
@Table(name = "rating_best_case")
public class BestCase {
    @Id private UUID id;
    private UUID analysisId;
    private UUID visitId;
    private UUID agentId;
    private LocalDate visitDate;
    private int score;
    @Column(columnDefinition = "text") private String summary;
    private boolean published;
    private Instant publishedAt;
    private UUID publishedBy;
    private Instant createdAt;

    protected BestCase() {}
    public BestCase(UUID analysisId, UUID visitId, UUID agentId, LocalDate visitDate, int score, String summary, Instant now) {
        this.id = UUID.randomUUID(); this.analysisId = analysisId; this.visitId = visitId; this.agentId = agentId; this.visitDate = visitDate;
        this.score = score; this.summary = summary; this.createdAt = now;
    }
    public void publish(UUID by, Instant now) { this.published = true; this.publishedBy = by; this.publishedAt = now; }

    public UUID getId() { return id; }
    public UUID getAnalysisId() { return analysisId; }
    public UUID getVisitId() { return visitId; }
    public UUID getAgentId() { return agentId; }
    public LocalDate getVisitDate() { return visitDate; }
    public int getScore() { return score; }
    public String getSummary() { return summary; }
    public boolean isPublished() { return published; }
    public Instant getPublishedAt() { return publishedAt; }
    public UUID getPublishedBy() { return publishedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
