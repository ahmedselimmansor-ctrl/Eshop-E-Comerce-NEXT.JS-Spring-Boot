package com.eshop.payment.domain;

import com.eshop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Every callback a payment provider sends, recorded before it is acted on.
 *
 * <p>The unique constraint on {@code (provider, event_id)} <b>is</b> the idempotency mechanism.
 * Providers retry aggressively — a callback that times out on our side arrives again minutes later,
 * and a webhook that captures a payment twice would mark the order paid twice, commit the stock
 * reservation twice, and send two confirmation emails. Inserting first means a duplicate hits the
 * constraint and is answered {@code 200} without reprocessing.
 *
 * <p>The row is written whether or not the signature verifies, so a forged or malformed callback
 * leaves a trace to investigate rather than vanishing.
 */
@Entity
@Table(name = "payment_webhook_events")
@Getter
@Setter
@NoArgsConstructor
public class PaymentWebhookEvent extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private PaymentProviderType provider;

    /**
     * The provider's own event identifier. Where a provider does not supply one, the transaction id
     * combined with the event type serves the same purpose.
     */
    @Column(name = "event_id", nullable = false, length = 160)
    private String eventId;

    @Column(name = "event_type", length = 60)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "signature")
    private String signature;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid = false;

    @Column(name = "processed", nullable = false)
    private boolean processed = false;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error")
    private String error;

    @Column(name = "attempts", nullable = false)
    private short attempts = 0;

    /*
     * There is deliberately no separate `receivedAt`. A row here is created exactly once, at
     * receipt, so `createdAt` from BaseEntity already is the arrival time — a second column would
     * only be a second thing to keep in sync.
     */

    public void markProcessed() {
        processed = true;
        processedAt = Instant.now();
        error = null;
    }

    public void recordFailure(String message) {
        attempts = (short) (attempts + 1);
        error = message == null ? null : message.substring(0, Math.min(message.length(), 1000));
    }
}
