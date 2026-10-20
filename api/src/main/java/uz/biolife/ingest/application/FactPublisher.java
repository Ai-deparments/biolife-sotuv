package uz.biolife.ingest.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a fact as a domain event inside a transaction, so Modulith's event publication
 * registry stores it (outbox) and every module listener receives it after commit.
 */
@Service
public class FactPublisher {
    private final ApplicationEventPublisher events;

    public FactPublisher(ApplicationEventPublisher events) { this.events = events; }

    @Transactional
    public void publish(Object event) {
        events.publishEvent(event);
    }
}
