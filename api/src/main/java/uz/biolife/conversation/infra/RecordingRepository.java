package uz.biolife.conversation.infra;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.biolife.conversation.domain.Recording;

public interface RecordingRepository extends JpaRepository<Recording, UUID> {
    /** Oldest job the worker may take: queued and due, or working with an expired lease. Row-locked, others skip it. */
    @Query(value = """
        select * from conv_recording
        where (status = 'QUEUED' and (lease_until is null or lease_until <= :now)) or (status = 'WORKING' and lease_until < :now)
        order by created_at limit 1 for update skip locked""", nativeQuery = true)
    Optional<Recording> nextDue(@Param("now") Instant now);

    @Query("select r from Recording r where r.status = 'DONE' and r.published = false")
    List<Recording> doneNotPublished();

    @Query("select r from Recording r where r.visitId is null and r.createdAt > :since")
    List<Recording> unlinkedSince(@Param("since") Instant since);

    List<Recording> findByAgentIdAndDateOrderByStartedAtDesc(UUID agentId, java.time.LocalDate date);

    long countByStatus(String status);

    List<Recording> findByDateBefore(java.time.LocalDate date);
}
