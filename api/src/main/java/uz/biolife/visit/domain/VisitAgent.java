package uz.biolife.visit.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read copy of agent master data for the visit list. */
@Entity(name = "VisitAgent")
@Table(name = "visit_agent")
public class VisitAgent {
    @Id private UUID id;
    private String name;
    private String region;
    private boolean active;

    protected VisitAgent() {}
    public VisitAgent(UUID id) { this.id = id; }
    public void update(String name, String region, boolean active) { this.name = name; this.region = region; this.active = active; }
    public UUID getId() { return id; }
}
