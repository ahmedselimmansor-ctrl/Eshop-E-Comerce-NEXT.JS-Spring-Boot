package com.eshop.order.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The order lifecycle, with its legal transitions encoded on the enum itself.
 *
 * <p>Keeping the state machine here rather than as scattered {@code if} statements in services
 * means every caller - the checkout flow, an admin action, a payment webhook, the expiry sweeper -
 * is constrained by the same rules. An order cannot go from {@code DELIVERED} back to
 * {@code PROCESSING} because someone clicked the wrong dropdown.
 *
 * <pre>
 *   PENDING_PAYMENT ──paid──► PAID ──► PROCESSING ──► SHIPPED ──► DELIVERED
 *         │                    │            │                         │
 *         ├──expired/cancel────┤            │                         │
 *         ▼                    ▼            ▼                         ▼
 *      CANCELLED           CANCELLED    CANCELLED            REFUNDED / PARTIALLY_REFUNDED
 *         │
 *      FAILED (payment declined)
 * </pre>
 */
public enum OrderStatus {

    /** Order created, stock held, awaiting payment. Released after 30 minutes if unpaid. */
    PENDING_PAYMENT,

    /** Payment captured. Stock has moved from reserved to sold. */
    PAID,

    /** Being picked and packed. */
    PROCESSING,

    SHIPPED,

    DELIVERED,

    /** Cancelled before dispatch, by the customer or an administrator. */
    CANCELLED,

    REFUNDED,

    PARTIALLY_REFUNDED,

    /** Payment failed or expired without a successful attempt. */
    FAILED;

    private static final Set<OrderStatus> TERMINAL =
            EnumSet.of(DELIVERED, CANCELLED, REFUNDED, FAILED);

    /** Statuses a customer may cancel from - nothing that has already left the warehouse. */
    private static final Set<OrderStatus> CUSTOMER_CANCELLABLE =
            EnumSet.of(PENDING_PAYMENT, PAID, PROCESSING);

    public Set<OrderStatus> allowedTransitions() {
        return switch (this) {
            case PENDING_PAYMENT -> EnumSet.of(PAID, CANCELLED, FAILED);
            case PAID -> EnumSet.of(PROCESSING, SHIPPED, CANCELLED, REFUNDED);
            case PROCESSING -> EnumSet.of(SHIPPED, CANCELLED, REFUNDED);
            // A shipment that comes back is recorded as a refund on the order; the physical return
            // itself lives on the `returns` table with its own approve/receive workflow.
            case SHIPPED -> EnumSet.of(DELIVERED, REFUNDED);
            case DELIVERED -> EnumSet.of(REFUNDED, PARTIALLY_REFUNDED);
            case PARTIALLY_REFUNDED -> EnumSet.of(REFUNDED);
            case CANCELLED, REFUNDED, FAILED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isCancellableByCustomer() {
        return CUSTOMER_CANCELLABLE.contains(this);
    }

    /** Whether stock is still held for this order and must be released on cancellation. */
    public boolean holdsReservation() {
        return this == PENDING_PAYMENT;
    }

    /** Whether the order counts toward revenue reporting. */
    public boolean isRevenueRecognised() {
        return this == PAID || this == PROCESSING || this == SHIPPED
                || this == DELIVERED || this == PARTIALLY_REFUNDED;
    }
}
