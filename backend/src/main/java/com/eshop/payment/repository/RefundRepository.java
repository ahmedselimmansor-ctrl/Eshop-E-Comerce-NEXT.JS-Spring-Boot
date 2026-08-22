package com.eshop.payment.repository;

import com.eshop.payment.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Refunds against a captured payment. */
@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    List<Refund> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    /**
     * Total already refunded against a payment. Only completed and in-flight refunds count -
     * a failed attempt must not reduce what can still be returned.
     */
    @Query("""
            SELECT COALESCE(SUM(r.amount), 0)
              FROM Refund r
             WHERE r.payment.id = :paymentId AND r.status <> 'FAILED'
            """)
    BigDecimal totalRefunded(@Param("paymentId") UUID paymentId);
}
