package uz.biolife.visit.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/** Planned visits of one agent on one day (route plan), for branch coverage. */
@Entity
@Table(name = "visit_plan_day")
public class PlanDay {
    @Id private UUID id;
    private UUID agentId;
    private LocalDate date;
    private int planned;

    protected PlanDay() {}
    public PlanDay(UUID agentId, LocalDate date) { this.id = idOf(agentId, date); this.agentId = agentId; this.date = date; }
    public static UUID idOf(UUID agentId, LocalDate date) { return UUID.nameUUIDFromBytes((agentId + ":" + date).getBytes(StandardCharsets.UTF_8)); }
    public void setPlanned(int planned) { this.planned = planned; }
}
