package uz.biolife.sources.application;

import java.time.Clock;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import uz.biolife.shared.events.DomainEvents.SourceSynced;
import uz.biolife.shared.persistence.AggregateLock;
import uz.biolife.sources.domain.SourceStatus;
import uz.biolife.sources.infra.SourceStatusRepository;

@Component
public class SourceListener {
    private final SourceStatusRepository sources;
    private final AggregateLock lock;
    private final Clock clock;

    public SourceListener(SourceStatusRepository sources, AggregateLock lock, Clock clock) { this.sources = sources; this.lock = lock; this.clock = clock; }

    @ApplicationModuleListener
    public void on(SourceSynced e) {
        if (e.source() == null || e.source().isBlank()) return;
        lock.acquire("source:" + e.source());
        SourceStatus s = sources.findById(e.source()).orElseGet(() -> new SourceStatus(e.source()));
        s.update(e.title(), e.syncedAt(), e.ok(), e.detail(), clock.instant());
        sources.save(s);
    }
}
