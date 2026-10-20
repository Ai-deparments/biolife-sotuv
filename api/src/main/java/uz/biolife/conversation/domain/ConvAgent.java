package uz.biolife.conversation.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity(name = "ConvAgent")
@Table(name = "conv_agent")
public class ConvAgent {
    @Id private UUID id;
    private String name;
    private String region;
    private boolean active;

    protected ConvAgent() {}
    public ConvAgent(UUID id) { this.id = id; }
    public void update(String name, String region, boolean active) { this.name = name; this.region = region; this.active = active; }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getRegion() { return region; }
}
