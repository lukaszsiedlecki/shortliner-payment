package com.shortliner.payment.outbox.repository;

import com.shortliner.payment.outbox.OutboxStatus;
import com.shortliner.payment.outbox.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks the next batch of PENDING rows for this transaction only.
     * SKIP LOCKED is what makes the publisher safe to run on every replica
     * at once: each instance grabs whatever isn't already locked by another
     * instance's in-flight batch, so nothing is published twice and nothing
     * blocks waiting on another instance's lock.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    /** Null when there are no rows in that status. Served by the (status, created_at) index. */
    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.status = :status")
    Instant findOldestCreatedAtByStatus(@Param("status") OutboxStatus status);
}
