package uz.biolife.shared.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * Serialises work on one aggregate across async listeners with a PostgreSQL transaction-scoped
 * advisory lock. Events for the same visit or agent-day often arrive back to back; without this
 * two threads would both try to insert the same read-model row.
 * Must be called inside the listener's transaction; the lock is released at commit/rollback.
 */
@Component
public class AggregateLock {
    private final EntityManager em;

    public AggregateLock(EntityManager em) { this.em = em; }

    public void acquire(String key) {
        em.createNativeQuery("select pg_advisory_xact_lock(hashtext(:k))").setParameter("k", key).getSingleResult();
    }
}
