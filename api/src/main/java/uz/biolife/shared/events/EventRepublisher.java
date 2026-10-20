package uz.biolife.shared.events;

import java.time.Duration;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-delivers event publications whose listener failed (e.g. DB hiccup) once they are a minute old. */
@Component
public class EventRepublisher {
    private final IncompleteEventPublications incomplete;

    public EventRepublisher(IncompleteEventPublications incomplete) { this.incomplete = incomplete; }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void resubmit() {
        incomplete.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1));
    }
}
