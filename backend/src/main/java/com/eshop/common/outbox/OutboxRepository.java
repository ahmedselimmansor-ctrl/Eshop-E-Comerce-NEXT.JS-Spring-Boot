package com.eshop.common.outbox;

import com.eshop.common.outbox.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Unpublished events, oldest first, capped by {@code Pageable}.
     *
     * <p>Events that have already failed {@code maxAttempts} times are excluded: a payload the
     * relay cannot publish would otherwise be retried forever at the head of the queue and starve
     * everything behind it. Those rows stay in the table for inspection.
     */
    @Query("""
            SELECT e FROM OutboxEvent e
             WHERE e.publishedAt IS NULL AND e.attempts < :maxAttempts
             ORDER BY e.createdAt ASC
            """)
    List<OutboxEvent> findPending(@Param("maxAttempts") int maxAttempts, Pageable pageable);

    @Query("""
            SELECT COUNT(e) FROM OutboxEvent e
             WHERE e.publishedAt IS NULL AND e.attempts >= :maxAttempts
            """)
    long countStuck(@Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
