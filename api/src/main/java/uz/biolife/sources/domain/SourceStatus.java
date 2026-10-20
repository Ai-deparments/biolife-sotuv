package uz.biolife.sources.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Last known state of one upstream source. */
@Entity
@Table(name = "source_status")
public class SourceStatus {
    @Id private String source;
    private String title;
    private Instant syncedAt;
    private boolean ok;
    private String detail;
    private Instant receivedAt;

    protected SourceStatus() {}
    public SourceStatus(String source) { this.source = source; }

    public void update(String title, Instant syncedAt, boolean ok, String detail, Instant now) {
        this.title = cut(title, 120); this.syncedAt = syncedAt; this.ok = ok; this.detail = cut(detail, 500); this.receivedAt = now;
    }
    private static String cut(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }

    public String getSource() { return source; }
    public String getTitle() { return title; }
    public Instant getSyncedAt() { return syncedAt; }
    public boolean isOk() { return ok; }
    public String getDetail() { return detail; }
    public Instant getReceivedAt() { return receivedAt; }
}
