package uz.biolife.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One recorded conversation and its analysis. Status: QUEUED → WORKING → DONE (or back to QUEUED, or FAILED). */
@Entity
@Table(name = "conv_recording")
public class Recording {
    public enum Status { QUEUED, WORKING, DONE, FAILED }

    @Id private UUID id;
    private UUID agentId;
    private Instant startedAt;
    private Instant endedAt;
    private int durationSec;
    private LocalDate date;
    private String mime;
    private long sizeBytes;
    private String filePath;
    private String status;
    private int attempts;
    private Instant leaseUntil;
    private String error;
    private UUID visitId;
    private String outletName;
    @Column(columnDefinition = "text") private String transcript;
    @Column(columnDefinition = "text") private String analysis;
    private Integer score;
    private Boolean promoMentioned;
    private String flags;
    @Column(columnDefinition = "text") private String summary;
    @Column(columnDefinition = "text") private String advice;
    private UUID analysisId;
    private boolean published;
    private Instant createdAt;
    private Instant updatedAt;

    protected Recording() {}
    public Recording(UUID id, UUID agentId, Instant startedAt, Instant endedAt, int durationSec, LocalDate date, String mime, long sizeBytes, String filePath, Instant now) {
        this.id = id; this.agentId = agentId; this.startedAt = startedAt; this.endedAt = endedAt; this.durationSec = durationSec; this.date = date;
        this.mime = mime; this.sizeBytes = sizeBytes; this.filePath = filePath; this.status = Status.QUEUED.name(); this.createdAt = now; this.updatedAt = now;
    }

    public void claim(Instant now, Instant leaseUntil) { status = Status.WORKING.name(); attempts++; this.leaseUntil = leaseUntil; error = null; updatedAt = now; }
    public void retryLater(String error, Instant notBefore, Instant now) { status = Status.QUEUED.name(); this.error = cut(error, 1000); leaseUntil = notBefore; updatedAt = now; }
    public void fail(String error, Instant now) { status = Status.FAILED.name(); this.error = cut(error, 1000); leaseUntil = null; updatedAt = now; }
    public void done(String transcript, String analysis, int score, boolean promo, String flags, String summary, String advice, Instant now) {
        status = Status.DONE.name(); this.transcript = transcript; this.analysis = analysis; this.score = score; this.promoMentioned = promo;
        this.flags = cut(flags, 400); this.summary = summary; this.advice = advice; this.analysisId = UUID.randomUUID(); leaseUntil = null; error = null; updatedAt = now;
    }
    public void linkVisit(UUID visitId, String outletName, Instant now) { this.visitId = visitId; this.outletName = cut(outletName, 255); updatedAt = now; }
    public void markPublished(Instant now) { published = true; updatedAt = now; }
    /** A wait (quota, provider busy) that should not use up one of the limited attempts. */
    public void uncountAttempt() { if (attempts > 0) attempts--; }
    /** Manual re-run from the panel: analysis again from the same audio. */
    public void requeue(Instant now) { status = Status.QUEUED.name(); attempts = 0; leaseUntil = null; error = null; updatedAt = now; }

    private static String cut(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }

    public UUID getId() { return id; }
    public UUID getAgentId() { return agentId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public int getDurationSec() { return durationSec; }
    public LocalDate getDate() { return date; }
    public String getMime() { return mime; }
    public long getSizeBytes() { return sizeBytes; }
    public String getFilePath() { return filePath; }
    public Status getStatus() { return Status.valueOf(status); }
    public int getAttempts() { return attempts; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public String getError() { return error; }
    public UUID getVisitId() { return visitId; }
    public String getOutletName() { return outletName; }
    public String getTranscript() { return transcript; }
    public String getAnalysis() { return analysis; }
    public Integer getScore() { return score; }
    public Boolean getPromoMentioned() { return promoMentioned; }
    public String getFlags() { return flags; }
    public String getSummary() { return summary; }
    public String getAdvice() { return advice; }
    public UUID getAnalysisId() { return analysisId; }
    public boolean isPublished() { return published; }
    public Instant getCreatedAt() { return createdAt; }
}
