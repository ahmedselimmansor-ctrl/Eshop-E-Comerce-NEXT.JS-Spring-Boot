package com.eshop.inventory.repository;

import com.eshop.inventory.domain.InventoryItem;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    /**
     * Loads the rows a checkout is about to reserve, holding a row-level write lock on each.
     *
     * <p>This is the oversell guard. Without {@code FOR UPDATE}, two concurrent checkouts both read
     * "1 available", both pass the check, and both reserve — the classic lost update. With it, the
     * second transaction blocks until the first commits and then re-reads the true remaining count.
     *
     * <p>Ordered by id so that two checkouts containing the same two SKUs always take the locks in
     * the same sequence. Unordered locking is how deadlocks appear under load.
     *
     * <p>The three-second timeout keeps a stuck transaction from queueing every other checkout
     * behind it; exceeding it surfaces as a lock-timeout exception that the caller reports as a
     * retryable conflict rather than a hang.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
            SELECT i FROM InventoryItem i
             WHERE i.id IN :ids
             ORDER BY i.id ASC
            """)
    List<InventoryItem> lockAllById(@Param("ids") List<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
    Optional<InventoryItem> lockById(@Param("id") UUID id);

    /**
     * Resolves the stock row for a SKU. The {@code variantId} branch is written out rather than
     * relying on {@code = NULL} semantics, which never matches in SQL.
     */
    @Query("""
            SELECT i FROM InventoryItem i
             WHERE i.product.id = :productId
               AND ((:variantId IS NULL AND i.variant IS NULL) OR i.variant.id = :variantId)
            """)
    List<InventoryItem> findForSku(@Param("productId") UUID productId,
                                   @Param("variantId") UUID variantId);

    @Query("SELECT i FROM InventoryItem i WHERE i.product.id IN :productIds")
    List<InventoryItem> findByProductIds(@Param("productIds") List<UUID> productIds);

    /**
     * Sellable units per product, summed across every warehouse and every variant.
     *
     * <p>Distinct from loading the rows and summing them in Java: a product listing wants one figure
     * per product, and a variable product with eight variants in three warehouses has twenty-four
     * rows behind that one number.
     */
    @Query("""
           SELECT i.product.id, SUM(i.quantityOnHand - i.quantityReserved)
             FROM InventoryItem i
            WHERE i.product.id IN :productIds
            GROUP BY i.product.id
           """)
    List<Object[]> sumAvailableByProduct(@Param("productIds") List<UUID> productIds);

    @Query("SELECT i FROM InventoryItem i WHERE i.variant.id IN :variantIds")
    List<InventoryItem> findByVariantIds(@Param("variantIds") List<UUID> variantIds);

    /** Total sellable units for a product across every warehouse. */
    @Query("""
            SELECT COALESCE(SUM(i.quantityOnHand - i.quantityReserved), 0)
              FROM InventoryItem i
             WHERE i.product.id = :productId
            """)
    int availableForProduct(@Param("productId") UUID productId);

    @Query("""
            SELECT COALESCE(SUM(i.quantityOnHand - i.quantityReserved), 0)
              FROM InventoryItem i
             WHERE i.variant.id = :variantId
            """)
    int availableForVariant(@Param("variantId") UUID variantId);

    /** Low-stock report. Compares against sellable stock, not the raw on-hand figure. */
    @EntityGraph(attributePaths = {"product", "variant", "warehouse"})
    @Query("""
            SELECT i FROM InventoryItem i
             WHERE i.quantityOnHand - i.quantityReserved <= i.reorderLevel
               AND (CAST(:warehouseId AS java.util.UUID) IS NULL OR i.warehouse.id = :warehouseId)
             ORDER BY (i.quantityOnHand - i.quantityReserved) ASC
            """)
    Page<InventoryItem> findLowStock(@Param("warehouseId") UUID warehouseId, Pageable pageable);

    @EntityGraph(attributePaths = {"product", "variant", "warehouse"})
    @Query("""
            SELECT i FROM InventoryItem i
             WHERE (CAST(:warehouseId AS java.util.UUID) IS NULL OR i.warehouse.id = :warehouseId)
               AND (CAST(:query AS STRING) IS NULL
                    OR LOWER(i.product.nameAr) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%'))
                    OR LOWER(i.product.sku) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%')))
            """)
    Page<InventoryItem> search(@Param("query") String query,
                               @Param("warehouseId") UUID warehouseId,
                               Pageable pageable);

    @Query("SELECT COUNT(i) FROM InventoryItem i WHERE i.quantityOnHand - i.quantityReserved <= 0")
    long countOutOfStock();
}
