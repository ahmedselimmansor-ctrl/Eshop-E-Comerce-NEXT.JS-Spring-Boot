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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * One payment attempt against an order.
 *
 * <h2>PCI scope</h2>
 * No card data ever reaches this system. Paymob's iframe and Fawry's hosted flow collect the PAN
 * directly, and what lands here is a provider identifier, the brand, and the last four digits -
 * enough to show "Visa ···· 4242" on a receipt and to reconcile with the provider, and nothing that
 * would put this database in PCI scope. There is deliberately no field for a PAN, a CVV, or an
 * expiry date, and {@code rawResponse} is redacted before it is written.
 *
 * <p>An order can have several payments: a failed card attempt followed by a successful one, or a
 * customer who switches from Fawry to cash on delivery. Only one reaches {@code CAPTURED}.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment extends AuditableEntity {

    public enum Status {
        /** Created; awaiting the customer to complete it at the provider. */
        PENDING,
        /** Funds held, not yet taken. */
        AUTHORIZED,
        /** Money taken. This is the state that moves the order to PAID. */
        CAPTURED,
        FAILED,
        CANCELLED,
        /** A Fawry reference or payment window that lapsed unpaid. */
        EXPIRED,
        REFUNDED,
        PARTIALLY_REFUNDED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private PaymentProviderType provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private PaymentMethod method;

    /** The provider's transaction identifier, used to reconcile and to request refunds. */
    @Column(name = "provider_payment_id", length = 120)
    private String providerPaymentId;

    /** The provider's own order/intent identifier, quoted back on their callbacks. */
    @Column(name = "provider_order_id", length = 120)
    private String providerOrderId;

    /** Fawry reference the customer quotes at the outlet. */
    @Column(name = "reference_code", length = 60)
    private String referenceCode;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = Money.DEFAULT_CURRENCY;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    /**
     * Guards against creating two payments for one checkout attempt. Unique at the database level,
     * so a duplicate is rejected by the constraint rather than by a racy read.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 80)
    private String idempotencyKey;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    /** Provider payload with sensitive keys stripped - see {@code PaymentPayloadRedactor}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private Map<String, Object> rawResponse;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // ------------------------------------------------------------- behaviour --

    public Money amountMoney() {
        return Money.of(amount, currency);
    }

    public boolean isCaptured() {
        return status == Status.CAPTURED || status == Status.PARTIALLY_REFUNDED;
    }

    public boolean isTerminal() {
        return status == Status.CAPTURED || status == Status.FAILED
                || status == Status.CANCELLED || status == Status.EXPIRED
                || status == Status.REFUNDED;
    }

    public boolean isExpired() {
        return status == Status.PENDING && expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    /**
     * Records a successful capture.
     *
     * <p>No-op when already captured, so a duplicate webhook delivery cannot double-stamp the
     * payment or re-trigger the order transition that follows it.
     */
    public void capture(String providerPaymentId, Map<String, Object> redactedResponse) {
        if (isCaptured()) {
            return;
        }
        this.status = Status.CAPTURED;
        this.capturedAt = Instant.now();
        if (providerPaymentId != null) {
            this.providerPaymentId = providerPaymentId;
        }
        if (redactedResponse != null) {
            this.rawResponse = redactedResponse;
        }
    }

    public void authorize(String providerPaymentId) {
        this.status = Status.AUTHORIZED;
        this.authorizedAt = Instant.now();
        this.providerPaymentId = providerPaymentId;
    }

    public void fail(String code, String reason, Map<String, Object> redactedResponse) {
        if (isCaptured()) {
            // A late failure callback arriving after a successful capture is ignored rather than
            // overwriting a settled payment.
            return;
        }
        this.status = Status.FAILED;
        this.failureCode = code;
        this.failureReason = reason;
        if (redactedResponse != null) {
            this.rawResponse = redactedResponse;
        }
    }

    public void expire() {
        if (status == Status.PENDING) {
            status = Status.EXPIRED;
        }
    }

    /** Records the card display details a provider returns on success. */
    public void applyCardDetails(String brand, String last4) {
        this.cardBrand = brand;
        // Belt and braces: truncate so a provider returning more than four digits cannot widen
        // what is stored.
        this.cardLast4 = last4 == null ? null : last4.substring(Math.max(0, last4.length() - 4));
    }
}
