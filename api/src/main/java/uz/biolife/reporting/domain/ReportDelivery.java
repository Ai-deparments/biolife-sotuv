package uz.biolife.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Audit of every report sent to the head of sales. */
@Entity
@Table(name = "report_delivery")
public class ReportDelivery {
    public enum Type { DAILY, WEEKLY }
    public enum Status { SENT, FAILED }

    @Id private UUID id;
    private String type;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String channel;
    private String recipient;
    @Column(columnDefinition = "text") private String body;
    private String status;
    private String error;
    private Instant sentAt;

    protected ReportDelivery() {}
    public ReportDelivery(Type type, LocalDate periodStart, LocalDate periodEnd, String channel, String recipient, String body, Status status, String error, Instant sentAt) {
        this.id = UUID.randomUUID(); this.type = type.name(); this.periodStart = periodStart; this.periodEnd = periodEnd; this.channel = channel;
        this.recipient = recipient; this.body = body; this.status = status.name(); this.error = error; this.sentAt = sentAt;
    }
    public UUID getId() { return id; }
    public String getType() { return type; }
    public LocalDate getPeriodStart() { return periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public String getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getBody() { return body; }
    public String getStatus() { return status; }
    public String getError() { return error; }
    public Instant getSentAt() { return sentAt; }
}
