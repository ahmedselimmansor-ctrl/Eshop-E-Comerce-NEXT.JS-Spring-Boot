package com.eshop.user.domain;

/** Lifecycle of a customer or staff account. */
public enum UserStatus {

    /** Normal account; may authenticate and place orders. */
    ACTIVE,

    /**
     * Blocked by an administrator. Authentication is refused with {@code ACCOUNT_SUSPENDED} and
     * existing refresh-token families are revoked, but all history is preserved.
     */
    SUSPENDED,

    /**
     * Self-service deletion. The row is retained with {@code deleted_at} set because orders,
     * invoices, and payment records must survive for accounting and dispute handling; personal
     * fields are scrubbed by {@code UserService.anonymise}.
     */
    DELETED
}
