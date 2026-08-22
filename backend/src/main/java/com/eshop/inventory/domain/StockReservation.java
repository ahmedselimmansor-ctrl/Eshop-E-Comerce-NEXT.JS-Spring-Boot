package com.eshop.inventory.domain;

import com.eshop.common.domain.BaseEntity;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A time-bounded hold on stock, created when an order is placed and resolved when payment settles.
 *
 * <p>The expiry is what makes the whole scheme safe. Without it, a customer who opens the Paymob
 * iframe and closes the tab holds those units forever, and a store running a flash sale strands its
 * entire inventory in abandoned checkouts. {@code ReservationSweeper} releases anything still
 * {@code HELD} past {@code expiresAt}.
 *
 * <p>{@code orderId} and {@code cartId} are plain UUIDs rather than entity references: inventory
 * must not depend on the order or cart domains, and the database still enforces referential
 * integrity through the foreign keys added in migration V6.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@Setter
@NoArgsConstructor
public class StockReservation extends BaseEntity {

    public enum Status {
        /** Active hold, counted in {@code quantityReserved}. */
        HELD,
        /** Payment captured; the hold became a {@code SALE} movement. */
        COMMITTED,
        /** Returned to sellable stock. */
        RELEASED
    }

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "cart_id")
    private UUID cartId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem inventoryItem;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.HELD;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    public boolean isHeld() {
        return status == Status.HELD;
    }

    public boolean isExpired() {
        return status == Status.HELD && expiresAt.isBefore(Instant.now());
    }

    public void commit() {
        status = Status.COMMITTED;
    }

    public void release() {
        status = Status.RELEASED;
        releasedAt = Instant.now();
    }

    public static StockReservation forOrder(UUID orderId, InventoryItem item, int quantity, Instant expiresAt) {
        StockReservation reservation = new StockReservation();
        reservation.setOrderId(orderId);
        reservation.setInventoryItem(item);
        reservation.setQuantity(quantity);
        reservation.setExpiresAt(expiresAt);
        return reservation;
    }
}
