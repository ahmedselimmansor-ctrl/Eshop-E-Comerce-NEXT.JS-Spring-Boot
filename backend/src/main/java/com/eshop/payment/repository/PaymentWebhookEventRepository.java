package com.eshop.payment.repository;

import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.payment.domain.PaymentWebhookEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The raw webhook log, kept for replay and for auditing signature failures. */
@Repository
public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, UUID> {

    boolean existsByProviderAndEventId(PaymentProviderType provider, String eventId);

    Optional<PaymentWebhookEvent> findByProviderAndEventId(PaymentProviderType provider, String eventId);

    /** Callbacks that were stored but whose processing failed - retried by a scheduled job. */
    @Query("""
            SELECT e FROM PaymentWebhookEvent e
             WHERE e.processed = FALSE AND e.signatureValid = TRUE AND e.attempts < :maxAttempts
             ORDER BY e.createdAt ASC
            """)
    List<PaymentWebhookEvent> findUnprocessed(@Param("maxAttempts") int maxAttempts, Pageable pageable);

    @Query("SELECT COUNT(e) FROM PaymentWebhookEvent e WHERE e.signatureValid = FALSE AND e.createdAt >= :since")
    long countInvalidSignaturesSince(@Param("since") Instant since);
}
