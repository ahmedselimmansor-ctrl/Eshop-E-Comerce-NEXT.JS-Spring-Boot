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

import java.util.UUID;

/**
 * One entry in the append-only stock ledger.
 *
 * <p>Every change to an {@link InventoryItem} writes a row here. The current balance is therefore
 * always explainable - "why does this SKU show 3 units when we received 50?" is answered by reading
 * the ledger rather than guessing. Rows are never updated or deleted; a mistake is corrected with a
 * compensating {@code ADJUSTMENT}, and a database trigger enforces that.
 */
@Entity
@Table(name = "inventory_movements")
@Getter
@Setter
@NoArgsConstructor
public class InventoryMovement extends BaseEntity {

    public enum Type {
        /** Goods received from a supplier. */
        RECEIPT,
        /** A reservation committed after payment - stock genuinely left the building. */
        SALE,
        /** A customer return restocked. */
        RETURN,
        /** Manual correction after a stock take. */
        ADJUSTMENT,
        /** Units held for a pending order. */
        RESERVATION,
        /** A hold given back: payment failed, order cancelled, or the hold expired. */
        RELEASE,
        /** Written off as damaged or expired. */
        DAMAGE,
        /** Moved between warehouses. */
        TRANSFER
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem inventoryItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private Type type;

    /** Signed: positive adds sellable stock, negative removes it. */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Available balance immediately after this movement, so the ledger reads without replaying. */
    @Column(name = "quantity_after")
    private Integer quantityAfter;

    /** What caused this movement: {@code ORDER}, {@code RETURN}, {@code STOCK_TAKE}, … */
    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "note")
    private String note;

    /** The administrator responsible; {@code null} for movements the system made on its own. */
    @Column(name = "created_by")
    private UUID createdBy;

    public static InventoryMovement of(InventoryItem item, Type type, int quantity,
                                       String referenceType, UUID referenceId, UUID actorId) {
        InventoryMovement movement = new InventoryMovement();
        movement.setInventoryItem(item);
        movement.setType(type);
        movement.setQuantity(quantity);
        movement.setQuantityAfter(item.available());
        movement.setReferenceType(referenceType);
        movement.setReferenceId(referenceId);
        movement.setCreatedBy(actorId);
        return movement;
    }
}
