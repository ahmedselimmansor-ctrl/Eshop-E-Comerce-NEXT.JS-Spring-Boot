package com.eshop.order.domain;

/**
 * How much of the order has physically shipped.
 *
 * <p>Separate from {@link OrderStatus} so that partial dispatch — three of five lines in the first
 * box — is representable without inventing an order status for every combination.
 */
public enum FulfillmentStatus {

    UNFULFILLED,

    /** Some lines shipped, others still pending. */
    PARTIALLY_FULFILLED,

    FULFILLED,

    RETURNED
}
