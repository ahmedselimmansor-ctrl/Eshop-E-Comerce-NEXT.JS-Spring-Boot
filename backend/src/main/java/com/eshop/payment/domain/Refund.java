package com.eshop.payment.domain;

import com.eshop.common.domain.AuditableEntity;
import com.eshop.common.money.Money;
import com.eshop.order.domain.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money returned to a customer against a captured {@link Payment}.
 *
 * <p>Partial refunds are first-class: a customer keeps two of three items, or is compensated for a
 * late delivery. Several refunds can accumulate against one payment, and
 * {@code PaymentService.refund} checks their sum against the captured amount before calling the
 * provider - refunding more than was taken is not a mistake any gateway will let you undo cleanly.
 */
@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
public class Refund extends AuditableEntity {

    public enum Status {
        /** Submitted to the provider, awaiting confirmation. */
        PENDING,
        COMPLETED,
        FAILED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 60)
    private String reason;

    @Column(name = "note")
    private String note;

    @Column(name = "provider_refund_id", length = 120)
    private String providerRefundId;

    /** Stops a double-clicked refund button from paying out twice. */
    @Column(name = "idempotency_key", unique = true, length = 80)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    /** The administrator who authorised it - always recorded, never nullable in practice. */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    public Money amountMoney() {
        return Money.of(amount, payment.getCurrency());
    }

    public void complete(String providerRefundId) {
        this.status = Status.COMPLETED;
        this.providerRefundId = providerRefundId;
        this.completedAt = Instant.now();
    }

    public void fail(String note) {
        this.status = Status.FAILED;
        this.note = note;
    }
}
