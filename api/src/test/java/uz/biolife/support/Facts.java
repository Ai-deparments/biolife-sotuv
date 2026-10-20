package uz.biolife.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import uz.biolife.ingest.application.FactPublisher;
import uz.biolife.shared.events.DomainEvents.AgentProfileChanged;
import uz.biolife.shared.events.DomainEvents.AnalysisCompleted;
import uz.biolife.shared.events.DomainEvents.DayPlanned;
import uz.biolife.shared.events.DomainEvents.OrderConfirmed;
import uz.biolife.shared.events.DomainEvents.PhotoChecked;
import uz.biolife.shared.events.DomainEvents.VisitClosed;
import uz.biolife.shared.events.DomainEvents.VisitStarted;
import uz.biolife.shared.time.WorkHours;

/** Fluent helper to publish a realistic visit in tests. */
public final class Facts {
    private final FactPublisher publisher;
    public Facts(FactPublisher publisher) { this.publisher = publisher; }

    public UUID agent(String name, String region) {
        UUID id = UUID.randomUUID();
        publisher.publish(new AgentProfileChanged(id, name, region, region + " sh.", true));
        return id;
    }

    public void planned(UUID agent, LocalDate date, int n) { publisher.publish(new DayPlanned(agent, date, n)); }

    public static Instant at(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date.atTime(hour, minute), WorkHours.ZONE).toInstant();
    }

    /** Full visit: started → closed (all proofs) → analysed → photo → order. Returns the visit id. */
    public UUID visit(UUID agent, LocalDate date, Instant start, int score, Map<String, Integer> criteria, boolean promo, List<String> flags, int blocks) {
        UUID visit = UUID.randomUUID();
        publisher.publish(new VisitStarted(visit, agent, start, true));
        publisher.publish(new VisitClosed(visit, agent, date, start.plusSeconds(900), true, false, !flags.isEmpty(), blocks));
        publisher.publish(new AnalysisCompleted(UUID.randomUUID(), visit, agent, date, score, criteria, promo, flags, "summary " + score));
        publisher.publish(new PhotoChecked(visit, agent, date, "ok", 14, 12));
        publisher.publish(new OrderConfirmed(visit, agent, date, blocks * 48_000L, blocks));
        return visit;
    }

    public void excused(UUID agent, LocalDate date, Instant start) {
        UUID visit = UUID.randomUUID();
        publisher.publish(new VisitStarted(visit, agent, start, true));
        publisher.publish(new VisitClosed(visit, agent, date, start.plusSeconds(60), false, true, false, 0));
    }

    public static Map<String, Integer> criteria(int salom, int ehtiyoj, int taklif, int aksiya, int yangi, int etiroz, int yakun) {
        return Map.of("salom", salom, "ehtiyoj", ehtiyoj, "taklif", taklif, "aksiya", aksiya, "yangi", yangi, "etiroz", etiroz, "yakun", yakun);
    }
}
