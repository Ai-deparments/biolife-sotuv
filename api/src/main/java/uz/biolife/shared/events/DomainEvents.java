package uz.biolife.shared.events;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event contracts between modules. Owner today: architecture (Sultan).
 * When visit / analysis / media / territory modules appear they become the publishers;
 * rating and reporting stay consumers and do not change. Documented in docs/events.md.
 */
public final class DomainEvents {
    private DomainEvents() {}

    /** Agent master data (from territory/identity). Consumers keep a small read copy. */
    public record AgentProfileChanged(UUID agentId, String name, String region, String district, boolean active) {}

    /** Plan for a day: how many visits were planned for the agent. */
    public record DayPlanned(UUID agentId, LocalDate date, int plannedVisits) {}

    /** Agent pressed "Keldim" and the server validated the geofence. */
    public record VisitStarted(UUID visitId, UUID agentId, Instant startedAt, boolean withinRadius) {}

    /** Visit closed with the four proofs resolved. */
    public record VisitClosed(UUID visitId, UUID agentId, LocalDate date, Instant closedAt,
                              boolean proofsComplete, boolean excused, boolean suspect, int blocks) {}

    /** Conversation analysed by the AI worker: score 0–100 with per-criterion points. */
    public record AnalysisCompleted(UUID analysisId, UUID visitId, UUID agentId, LocalDate date, int score,
                                    Map<String, Integer> criteria, boolean promoMentioned,
                                    List<String> flags, String summary) {}

    /** Shelf photo check result from the AI worker. */
    public record PhotoChecked(UUID visitId, UUID agentId, LocalDate date, String verdict, int facings, int expected) {}

    /** Order confirmed in Linko for the visit. Amount in UZS. */
    public record OrderConfirmed(UUID visitId, UUID agentId, LocalDate date, long amountUzs, int blocks) {}

    /**
     * A field visit as the source recorded it (Linko visit report via Kuzatuv): what the manager's visit list shows.
     * Upserted by visitId; the source re-sends it whenever something changes (end time, photos, orders).
     * {@code orders}/{@code ordersUzs} are orders at this outlet that day, {@code photosSuspect} are photos whose
     * capture time the source does not trust, {@code photoUrls} are up to six shelf photos. {@code recordedAt} is when the
     * source produced this version: an older version arriving late never overwrites a newer one.
     */
    public record VisitRecorded(UUID visitId, UUID agentId, LocalDate date, Instant startedAt, Instant endedAt,
                                String outletRef, String outletName, Integer distanceM, boolean withinRadius, Boolean inPlan,
                                int photos, int photosSuspect, List<String> photoUrls, int orders, long ordersUzs, Instant recordedAt) {}

    /** Freshness of an upstream data source (the Kuzatuv bridge, Linko visits, RITM orders…). */
    public record SourceSynced(String source, String title, Instant syncedAt, boolean ok, String detail) {}
}
