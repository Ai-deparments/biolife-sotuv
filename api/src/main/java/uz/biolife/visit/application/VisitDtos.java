package uz.biolife.visit.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VisitDtos {
    private VisitDtos() {}

    /** One visit in the manager's list. {@code ordersUzs} are orders at this outlet that day; {@code photoAi} is null until the AI checked the photo. */
    public record VisitRow(UUID visitId, UUID agentId, String agentName, String region, LocalDate date, Instant startedAt, Instant endedAt,
                           Integer minutes, String outletRef, String outletName, Integer distanceM, boolean withinRadius, Boolean inPlan,
                           int photos, int photosSuspect, List<String> photoUrls, int orders, long ordersUzs, PhotoAi photoAi) {}

    /** What the AI saw on the shelf photo. verdict: ok | low | none | unclear | not_shelf; brands: BioLife facings per brand. */
    public record PhotoAi(String verdict, String kind, int facings, int expected, Map<String, Integer> brands, List<String> competitors,
                          String note, Instant checkedAt) {}

    /** A page of visits plus the size of every filter (for the filter chips) and the branches to choose from. */
    public record VisitPage(LocalDate date, String kind, int page, int size, long total, Map<String, Long> counts,
                            List<String> regions, List<VisitRow> items) {}

    /** Coverage of one branch over a period: plan vs visits, out-of-plan, far, with photo, with order. */
    public record RegionRow(String region, int agents, int agentsActive, int planned, int visits, int inPlanVisits, int coveragePct,
                            int outPlanVisits, int farVisits, int withOrder, int orderRatePct, int withPhoto, int photoRatePct, int photosSuspect) {}

    /** Visit checks of one agent on one day (photo page). {@code aiChecked}/{@code aiOk}: photos the AI checked / that met the standard. */
    public record AgentVisitRow(UUID agentId, String name, String region, int visits, int far, int outPlan, int noOrder,
                                int noPhoto, int oldPhoto, int photos, int aiChecked, int aiOk) {}
}
