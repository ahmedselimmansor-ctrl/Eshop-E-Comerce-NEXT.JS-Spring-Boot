package com.eshop.order.domain;

/**
 * Payment state of an order, tracked separately from {@link OrderStatus}.
 *
 * <p>The two genuinely diverge: a cash-on-delivery order is {@code SHIPPED} while its payment is
 * still {@code PENDING}, and a delivered order can later become {@code REFUNDED} without changing
 * where the goods are. Collapsing them into one field forces every consumer to reconstruct the
 * distinction from context.
 */
public enum PaymentStatus {

    /** No successful attempt yet — awaiting a card payment, a Fawry reference, or cash on delivery. */
    PENDING,

    /** Funds held but not yet captured. Used where a provider separates the two steps. */
    AUTHORIZED,

    PAID,

    FAILED,

    REFUNDED,

    PARTIALLY_REFUNDED;

    public boolean isSettled() {
        return this == PAID || this == PARTIALLY_REFUNDED;
    }

    public boolean isRefundable() {
        return this == PAID || this == PARTIALLY_REFUNDED;
    }
}
