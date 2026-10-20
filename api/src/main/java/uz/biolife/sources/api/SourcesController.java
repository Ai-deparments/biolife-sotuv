package uz.biolife.sources.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.shared.security.CurrentUser;
import uz.biolife.sources.domain.SourceStatus;
import uz.biolife.sources.infra.SourceStatusRepository;

/** How fresh the panel's data is, per upstream source. */
@RestController
@RequestMapping("/api/v1/panel/sources")
public class SourcesController {
    public record SourceDto(String source, String title, Instant syncedAt, Long ageMinutes, boolean ok, String detail) {}

    private final SourceStatusRepository sources;
    private final Clock clock;

    public SourcesController(SourceStatusRepository sources, Clock clock) { this.sources = sources; this.clock = clock; }

    @GetMapping
    public List<SourceDto> list(CurrentUser user) {
        user.requireManager();
        Instant now = clock.instant();
        return sources.findAll().stream()
            .sorted(Comparator.comparing(SourceStatus::getSource))
            .map(s -> new SourceDto(s.getSource(), s.getTitle(), s.getSyncedAt(),
                s.getSyncedAt() == null ? null : Math.max(0, Duration.between(s.getSyncedAt(), now).toMinutes()), s.isOk(), s.getDetail()))
            .toList();
    }
}
