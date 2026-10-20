package uz.biolife.reporting.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Idempotency guard: "<kind>:<id>" keys of events already folded into report_agent_day. */
@Entity
@Table(name = "report_event_seen")
public class EventSeen {
    @Id private String key;
    protected EventSeen() {}
    public EventSeen(String key) { this.key = key; }
    public String getKey() { return key; }
}
