package com.eshop.payment.repository;

import com.eshop.payment.domain.Payment;
import com.eshop.payment.domain.PaymentProviderType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Payments. */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @EntityGraph(attributePaths = "order")
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * Resolves a payment from a provider callback.
     *
     * <p>Matched on either identifier: Paymob quotes back the order id it was given, while its
     * transaction id only exists after the customer completes the payment.
     */
    @EntityGraph(attributePaths = "order")
    @Query("""
            SELECT p FROM Payment p
             WHERE p.provider = :provider
               AND (p.providerPaymentId = :reference OR p.providerOrderId = :reference)
             ORDER BY p.createdAt DESC
             LIMIT 1
            """)
    Optional<Payment> findByProviderReference(@Param("provider") PaymentProviderType provider,
                                              @Param("reference") String reference);

    @EntityGraph(attributePaths = "order")
    Optional<Payment> findByReferenceCode(String referenceCode);

    /**
     * Loads a payment with a row lock, for the webhook path.
     *
     * <p>Two concurrent deliveries of the same callback would otherwise both read
     * {@code PENDING} and both capture. The lock serialises them; the second sees
     * {@code CAPTURED} and no-ops.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> lockById(@Param("id") UUID id);

    @EntityGraph(attributePaths = "order")
    List<Payment> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    @Query("""
            SELECT p FROM Payment p
             WHERE p.order.id = :orderId AND p.status IN ('CAPTURED', 'PARTIALLY_REFUNDED')
             ORDER BY p.capturedAt DESC
             LIMIT 1
            """)
    Optional<Payment> findCapturedForOrder(@Param("orderId") UUID orderId);

    /** Pending payments whose window has lapsed - swept to {@code EXPIRED}. */
    @Query("""
            SELECT p FROM Payment p
             WHERE p.status = 'PENDING' AND p.expiresAt IS NOT NULL AND p.expiresAt < :now
             ORDER BY p.expiresAt ASC
            """)
    List<Payment> findExpired(@Param("now") Instant now, Pageable pageable);

    @Query("""
            SELECT COUNT(p) FROM Payment p
             WHERE p.status = 'FAILED' AND p.createdAt >= :since
            """)
    long countFailedSince(@Param("since") Instant since);
}
