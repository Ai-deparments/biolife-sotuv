package uz.biolife.rating.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read copy of agent master data, fed by AgentProfileChanged. */
@Entity(name = "RatingAgent")
@Table(name = "rating_agent")
public class AgentRef {
    @Id private UUID id;
    private String name;
    private String region;
    private String district;
    private boolean active;

    protected AgentRef() {}
    public AgentRef(UUID id, String name, String region, String district, boolean active) {
        this.id = id; this.name = name; this.region = region; this.district = district; this.active = active;
    }
    public void update(String name, String region, String district, boolean active) {
        this.name = name; this.region = region; this.district = district; this.active = active;
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getRegion() { return region; }
    public String getDistrict() { return district; }
    public boolean isActive() { return active; }
}
