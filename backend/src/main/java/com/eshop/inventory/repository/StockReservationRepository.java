package com.eshop.inventory.repository;

import com.eshop.inventory.domain.StockReservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    @EntityGraph(attributePaths = "inventoryItem")
    List<StockReservation> findByOrderIdAndStatus(UUID orderId, StockReservation.Status status);

    @EntityGraph(attributePaths = "inventoryItem")
    List<StockReservation> findByOrderId(UUID orderId);

    /**
     * Expired holds, oldest first, in bounded batches.
     *
     * <p>Batched rather than "all expired" because a mail outage or a payment-provider incident can
     * strand thousands at once, and one sweep that tries to release them all in a single transaction
     * would hold locks long enough to stall live checkouts.
     */
    @EntityGraph(attributePaths = "inventoryItem")
    @Query("""
            SELECT r FROM StockReservation r
             WHERE r.status = 'HELD' AND r.expiresAt < :now
             ORDER BY r.expiresAt ASC
            """)
    List<StockReservation> findExpired(@Param("now") Instant now, Pageable pageable);

    @Query("SELECT COUNT(r) FROM StockReservation r WHERE r.status = 'HELD' AND r.expiresAt < :now")
    long countExpired(@Param("now") Instant now);
}
