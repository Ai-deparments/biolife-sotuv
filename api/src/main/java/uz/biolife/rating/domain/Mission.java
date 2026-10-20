package uz.biolife.rating.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** Weekly improvement mission for one agent, derived from the weakest criterion. */
@Entity
@Table(name = "rating_mission")
public class Mission {
    @Id private UUID id;
    private UUID agentId;
    private LocalDate weekStart;
    private String criterionCode;
    private int current;
    private int target;
    @Column(columnDefinition = "text") private String text;

    protected Mission() {}
    public Mission(UUID agentId, LocalDate weekStart, String criterionCode, int current, int target, String text) {
        this.id = UUID.randomUUID(); this.agentId = agentId; this.weekStart = weekStart; this.criterionCode = criterionCode;
        this.current = current; this.target = target; this.text = text;
    }
    public void refresh(String criterionCode, int current, int target, String text) {
        this.criterionCode = criterionCode; this.current = current; this.target = target; this.text = text;
    }
    public UUID getId() { return id; }
    public UUID getAgentId() { return agentId; }
    public LocalDate getWeekStart() { return weekStart; }
    public String getCriterionCode() { return criterionCode; }
    public int getCurrent() { return current; }
    public int getTarget() { return target; }
    public String getText() { return text; }
}
