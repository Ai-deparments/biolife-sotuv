package uz.biolife.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Small key/value memory of the reporting module (e.g. the last public address announced per channel). */
@Entity
@Table(name = "report_kv")
public class ReportKv {
    @Id private String key;
    @Column(columnDefinition = "text") private String value;
    private Instant updatedAt;

    protected ReportKv() {}
    public ReportKv(String key) { this.key = key; }
    public void set(String value, Instant now) { this.value = value; this.updatedAt = now; }
    public String getValue() { return value; }
}
