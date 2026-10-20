package uz.biolife.conversation.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Copy of a Linko visit (VisitRecorded) used to tell which shop a recording was made in. */
@Entity
@Table(name = "conv_visit")
public class ConvVisit {
    @Id private UUID visitId;
    private UUID agentId;
    private LocalDate date;
    private Instant startedAt;
    private Instant endedAt;
    private String outletName;

    protected ConvVisit() {}
    public ConvVisit(UUID visitId) { this.visitId = visitId; }
    public void update(UUID agentId, LocalDate date, Instant startedAt, Instant endedAt, String outletName) {
        this.agentId = agentId; this.date = date; this.startedAt = startedAt; this.endedAt = endedAt;
        this.outletName = outletName == null ? null : outletName.length() <= 255 ? outletName : outletName.substring(0, 255);
    }
    public UUID getVisitId() { return visitId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public String getOutletName() { return outletName; }
}
