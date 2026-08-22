package com.eshop.inventory.domain;

import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductVariant;
import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stock level for one SKU in one warehouse.
 *
 * <h2>Two counters, not one</h2>
 * {@code quantityOnHand} is what is physically present. {@code quantityReserved} is how much of that
 * is already promised to orders awaiting payment. <b>Sellable stock is the difference</b>, and every
 * availability check must use {@link #available()} rather than reading {@code quantityOnHand}.
 *
 * <p>Decrementing on-hand at checkout instead would be simpler but wrong: an order that is never
 * paid would permanently destroy inventory, and reversing it means an error-prone compensating
 * write. With a reservation, an abandoned checkout simply expires and the sweeper releases the hold.
 *
 * <h2>Concurrency</h2>
 * Two shoppers taking the last unit at the same moment is the canonical oversell. Two independent
 * guards apply: {@code InventoryService} takes a pessimistic {@code SELECT … FOR UPDATE} before
 * reserving, and the {@code ck_inventory_reserved_lte_on_hand} check constraint means that even a
 * code path that forgets the lock cannot commit a negative available balance. The {@code @Version}
 * column additionally protects concurrent admin adjustments, which do not take the row lock.
 */
@Entity
@Table(name = "inventory_items")
@Getter
@Setter
@NoArgsConstructor
public class InventoryItem extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** {@code null} for a {@code SIMPLE} product, which has no variants. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand = 0;

    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved = 0;

    /** Threshold that puts this SKU on the admin low-stock report. */
    @Column(name = "reorder_level", nullable = false)
    private int reorderLevel = 5;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // ------------------------------------------------------------- behaviour --

    /** Units a customer can actually buy right now. */
    public int available() {
        return quantityOnHand - quantityReserved;
    }

    public boolean canReserve(int quantity) {
        return quantity > 0 && available() >= quantity;
    }

    public boolean isLowStock() {
        return available() <= reorderLevel;
    }

    public boolean isOutOfStock() {
        return available() <= 0;
    }

    /**
     * Holds units for a pending order.
     *
     * @throws IllegalStateException if the hold would exceed what is on hand — a guard against a
     *                               caller that skipped the row lock, not the primary defence
     */
    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException(
                    "Cannot reserve " + quantity + "; only " + available() + " available");
        }
        quantityReserved += quantity;
    }

    /** Returns a hold to the sellable pool: payment failed, order cancelled, or the hold expired. */
    public void release(int quantity) {
        quantityReserved = Math.max(0, quantityReserved - quantity);
    }

    /**
     * Converts a hold into a sale once payment is captured: the units leave both counters, which is
     * the moment stock genuinely decreases.
     */
    public void commitReservation(int quantity) {
        int committed = Math.min(quantity, quantityReserved);
        quantityReserved -= committed;
        quantityOnHand = Math.max(0, quantityOnHand - committed);
    }

    /** Goods received, or a returned item restocked. */
    public void receive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Receipt quantity must be positive");
        }
        quantityOnHand += quantity;
    }

    /**
     * Sets an absolute count after a physical stock take.
     *
     * <p>Refuses to drop below what is already reserved: those units are promised to orders that are
     * mid-payment, and the check constraint would reject the write anyway.
     */
    public void adjustTo(int newOnHand) {
        if (newOnHand < 0) {
            throw new IllegalArgumentException("On-hand quantity cannot be negative");
        }
        if (newOnHand < quantityReserved) {
            throw new IllegalStateException(
                    "Cannot set on-hand to " + newOnHand + "; " + quantityReserved + " units are reserved");
        }
        quantityOnHand = newOnHand;
    }

    /** SKU of the variant when present, otherwise of the product. */
    public String sku() {
        return variant != null ? variant.getSku() : product.getSku();
    }
}
