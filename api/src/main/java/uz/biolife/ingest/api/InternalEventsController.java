package uz.biolife.ingest.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.ingest.application.FactPublisher;
import uz.biolife.shared.events.DomainEvents;
import uz.biolife.shared.security.CurrentUser;

/**
 * POST /api/v1/internal/events/{type} with the event JSON body. Service callers only (ADMIN role in dev mode).
 * Types: AgentProfileChanged, DayPlanned, VisitStarted, VisitClosed, AnalysisCompleted, PhotoChecked, OrderConfirmed,
 * VisitRecorded (visit list read model), SourceSynced (data freshness).
 */
@RestController
@RequestMapping("/api/v1/internal/events")
public class InternalEventsController {
    private static final Map<String, Class<?>> TYPES = Map.of(
        "AgentProfileChanged", DomainEvents.AgentProfileChanged.class,
        "DayPlanned", DomainEvents.DayPlanned.class,
        "VisitStarted", DomainEvents.VisitStarted.class,
        "VisitClosed", DomainEvents.VisitClosed.class,
        "AnalysisCompleted", DomainEvents.AnalysisCompleted.class,
        "PhotoChecked", DomainEvents.PhotoChecked.class,
        "OrderConfirmed", DomainEvents.OrderConfirmed.class,
        "VisitRecorded", DomainEvents.VisitRecorded.class,
        "SourceSynced", DomainEvents.SourceSynced.class);

    private final FactPublisher publisher;
    private final ObjectMapper mapper;

    public InternalEventsController(FactPublisher publisher, ObjectMapper mapper) {
        this.publisher = publisher;
        this.mapper = mapper;
    }

    @PostMapping("/{type}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void publish(@PathVariable String type, @RequestBody JsonNode body, CurrentUser user) {
        if (user.role() != CurrentUser.Role.ADMIN) throw new uz.biolife.shared.security.ForbiddenException("Faqat servis");
        Class<?> clazz = TYPES.get(type);
        if (clazz == null) throw new IllegalArgumentException("Noma'lum voqea turi: " + type);
        publisher.publish(mapper.convertValue(body, clazz));
    }
}
