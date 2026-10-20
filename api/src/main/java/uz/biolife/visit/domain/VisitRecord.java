package uz.biolife.visit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import uz.biolife.shared.events.DomainEvents.VisitRecorded;

/** One field visit as the source recorded it. Upserted by visit id. */
@Entity
@Table(name = "visit_record")
public class VisitRecord {
    @Id private UUID visitId;
    private UUID agentId;
    private LocalDate date;
    private Instant startedAt;
    private Instant endedAt;
    private String outletRef;
    private String outletName;
    @Column(name = "distance_m") private Integer distanceM;
    private boolean withinRadius;
    private Boolean inPlan;
    private int photos;
    private int photosSuspect;
    @Column(columnDefinition = "text") private String photoUrls;
    private int orders;
    private long ordersUzs;
    private Instant recordedAt;
    private Instant updatedAt;

    protected VisitRecord() {}
    public VisitRecord(UUID visitId) { this.visitId = visitId; }

    /** Applies the event unless it is older than what is stored. Returns false when skipped. */
    public boolean apply(VisitRecorded e, Instant now) {
        if (recordedAt != null && e.recordedAt() != null && e.recordedAt().isBefore(recordedAt)) return false;
        this.recordedAt = e.recordedAt();
        this.agentId = e.agentId(); this.date = e.date(); this.startedAt = e.startedAt(); this.endedAt = e.endedAt();
        this.outletRef = cut(e.outletRef(), 64); this.outletName = cut(e.outletName(), 255);
        this.distanceM = e.distanceM(); this.withinRadius = e.withinRadius(); this.inPlan = e.inPlan();
        this.photos = Math.max(0, e.photos()); this.photosSuspect = Math.max(0, e.photosSuspect());
        List<String> urls = e.photoUrls() == null ? List.of() : e.photoUrls().stream().filter(u -> u != null && u.startsWith("https://")).limit(6).toList();
        this.photoUrls = urls.isEmpty() ? null : String.join("\n", urls);
        this.orders = Math.max(0, e.orders()); this.ordersUzs = e.ordersUzs(); this.updatedAt = now;
        return true;
    }

    private static String cut(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }

    public UUID getVisitId() { return visitId; }
    public UUID getAgentId() { return agentId; }
    public LocalDate getDate() { return date; }
}
