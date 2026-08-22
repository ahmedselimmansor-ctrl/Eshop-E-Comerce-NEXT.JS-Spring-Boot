package com.eshop.admin.web.dto;

import com.eshop.inventory.domain.InventoryItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * A stock level as the console shows it.
 *
 * <p>Three quantities, and the difference between them is the point: {@code onHand} is what is
 * physically in the warehouse, {@code reserved} is how much of that unpaid orders are holding, and
 * {@code available} is the difference - the only one that decides whether a customer can buy. A
 * screen that showed only {@code onHand} is how a store oversells.
 */
@Schema(name = "StockRow")
public record StockRow(UUID id, UUID productId, UUID variantId, String productName, String sku,
                       String warehouse, int onHand, int reserved, int available,
                       int reorderLevel, boolean lowStock) {

    /**
     * Reads four lazy associations, so it must be called with a live session - either inside a
     * transaction or on an entity loaded through the {@code @EntityGraph} the list queries declare.
     */
    public static StockRow from(InventoryItem item) {
        return new StockRow(
                item.getId(),
                item.getProduct().getId(),
                item.getVariant() == null ? null : item.getVariant().getId(),
                item.getProduct().getNameAr(),
                item.sku(),
                item.getWarehouse().getCode(),
                item.getQuantityOnHand(),
                item.getQuantityReserved(),
                item.available(),
                item.getReorderLevel(),
                item.isLowStock());
    }
}
