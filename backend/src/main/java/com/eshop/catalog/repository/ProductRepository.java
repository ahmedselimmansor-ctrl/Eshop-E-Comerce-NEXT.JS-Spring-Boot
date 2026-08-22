package com.eshop.catalog.repository;

import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Product persistence.
 *
 * <p>{@link JpaSpecificationExecutor} backs the filtered listing: price ranges, brand sets, and an
 * arbitrary number of attribute facets cannot be expressed as one static JPQL query without either
 * a combinatorial explosion of methods or a string-concatenated query. Specifications compose the
 * predicate from whichever filters the caller actually supplied — see {@code ProductSpecifications}.
 *
 * <p>The storefront normally reaches this through Elasticsearch; these queries serve the admin
 * console, the search fallback when the cluster is down, and the reindexing job.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    /**
     * Product detail page. The entity graph fetches everything the page renders in one round trip;
     * without it this is a textbook N+1 (one query per image, per variant, per spec row).
     *
     * <p>{@code variants.attributeValues.attributeValue} is included so the variant selector can be
     * rendered without touching the database again.
     */
    @EntityGraph(attributePaths = {
            "brand", "primaryCategory", "images",
            "variants", "variants.attributeValues", "variants.attributeValues.attributeValue",
            "attributeValues", "attributeValues.attribute", "attributeValues.attributeValue"})
    @Query("SELECT p FROM Product p WHERE p.slug = :slug AND p.deletedAt IS NULL")
    Optional<Product> findDetailBySlug(@Param("slug") String slug);

    @EntityGraph(attributePaths = {
            "brand", "primaryCategory", "images",
            "variants", "variants.attributeValues", "variants.attributeValues.attributeValue",
            "attributeValues", "attributeValues.attribute", "attributeValues.attributeValue"})
    @Query("SELECT p FROM Product p WHERE p.id = :id AND p.deletedAt IS NULL")
    Optional<Product> findDetailById(@Param("id") UUID id);

    /** Listing projection — brand and images only, which is all a product card needs. */
    @EntityGraph(attributePaths = {"brand", "images"})
    @Query("SELECT p FROM Product p WHERE p.id IN :ids AND p.deletedAt IS NULL")
    List<Product> findCardsByIds(@Param("ids") List<UUID> ids);

    Optional<Product> findBySlugAndDeletedAtIsNull(String slug);

    Optional<Product> findBySkuAndDeletedAtIsNull(String sku);

    boolean existsBySlugAndDeletedAtIsNull(String slug);

    boolean existsBySkuAndDeletedAtIsNull(String sku);

    @EntityGraph(attributePaths = "brand")
    @Query("""
            SELECT p FROM Product p
             WHERE p.status = 'ACTIVE' AND p.deletedAt IS NULL AND p.featured = TRUE
             ORDER BY p.publishedAt DESC
            """)
    List<Product> findFeatured(Pageable pageable);

    @EntityGraph(attributePaths = "brand")
    @Query("""
            SELECT p FROM Product p
             WHERE p.status = 'ACTIVE' AND p.deletedAt IS NULL
             ORDER BY p.publishedAt DESC
            """)
    List<Product> findNewArrivals(Pageable pageable);

    @EntityGraph(attributePaths = "brand")
    @Query("""
            SELECT p FROM Product p
             WHERE p.status = 'ACTIVE' AND p.deletedAt IS NULL AND p.soldCount > 0
             ORDER BY p.soldCount DESC
            """)
    List<Product> findBestSellers(Pageable pageable);

    /**
     * Related products: same category, same brand as a fallback, never the product itself.
     * Ordered by sales so the suggestions are ones that actually convert.
     */
    @EntityGraph(attributePaths = "brand")
    @Query("""
            SELECT p FROM Product p
             WHERE p.status = 'ACTIVE' AND p.deletedAt IS NULL AND p.id <> :productId
               AND (p.primaryCategory.id = :categoryId OR p.brand.id = :brandId)
             ORDER BY
               CASE WHEN p.primaryCategory.id = :categoryId THEN 0 ELSE 1 END,
               p.soldCount DESC
            """)
    List<Product> findRelated(@Param("productId") UUID productId,
                              @Param("categoryId") UUID categoryId,
                              @Param("brandId") UUID brandId,
                              Pageable pageable);

    /**
     * Products bought in the same orders as this one, most frequently co-purchased first.
     * Native because it aggregates across the order tables, which the catalog domain does not map.
     */
    @Query(value = """
            SELECT oi2.product_id
              FROM order_items oi1
              JOIN order_items oi2 ON oi1.order_id = oi2.order_id AND oi2.product_id <> oi1.product_id
              JOIN products p ON p.id = oi2.product_id AND p.status = 'ACTIVE' AND p.deleted_at IS NULL
             WHERE oi1.product_id = :productId
             GROUP BY oi2.product_id
             ORDER BY COUNT(*) DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findFrequentlyBoughtTogether(@Param("productId") UUID productId, @Param("limit") int limit);

    /** Admin listing with the free-text and status filters that do not need a Specification. */
    @EntityGraph(attributePaths = {"brand", "primaryCategory"})
    @Query("""
            SELECT p FROM Product p
             WHERE p.deletedAt IS NULL
               AND (CAST(:status AS STRING) IS NULL OR p.status = :status)
               AND (CAST(:query AS STRING) IS NULL
                    OR LOWER(p.nameAr) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%'))
                    OR LOWER(p.nameEn) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%'))
                    OR LOWER(p.sku) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%')))
            """)
    Page<Product> adminSearch(@Param("query") String query,
                              @Param("status") ProductStatus status,
                              Pageable pageable);

    /** Feeds the reindexing job in stable id order so paging cannot skip or repeat a row. */
    @EntityGraph(attributePaths = {"brand", "primaryCategory"})
    @Query("""
            SELECT p FROM Product p
             WHERE p.deletedAt IS NULL AND p.id > :afterId
             ORDER BY p.id ASC
            """)
    List<Product> findForIndexing(@Param("afterId") UUID afterId, Pageable pageable);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.deletedAt IS NULL AND p.status = :status")
    long countByStatus(@Param("status") ProductStatus status);

    /**
     * Fire-and-forget view counter.
     *
     * <p>A bare UPDATE rather than a read-modify-write so concurrent views cannot lose increments,
     * and so the product's {@code @Version} is untouched — a page view should never make a
     * concurrent admin edit fail with an optimistic-lock error.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Query("UPDATE Product p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Product p SET p.soldCount = p.soldCount + :quantity WHERE p.id = :id")
    void incrementSoldCount(@Param("id") UUID id, @Param("quantity") int quantity);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.deletedAt IS NULL AND p.createdAt >= :since")
    long countCreatedSince(@Param("since") Instant since);

    // ------------------------------------------------------- fallback facets --
    // Used only when Elasticsearch is unavailable. Counts are scoped to the category, not to the
    // full active filter set — see ProductQueryService#degradedFacets for why that trade-off is
    // acceptable in a fallback path.

    /*
     * Two methods rather than one with an optional filter.
     *
     * `IN :ids` cannot be neutralised by passing null — JPQL has no meaning for a null collection,
     * and an empty list renders as `in ()`, which PostgreSQL rejects. Splitting the query is both
     * valid and one predicate cheaper on the unfiltered path, which is the common one.
     */
    @Query("""
            SELECT b.id AS id, b.slug AS slug, b.nameAr AS nameAr, b.nameEn AS nameEn, COUNT(p) AS total
              FROM Product p JOIN p.brand b
             WHERE p.status = 'ACTIVE' AND p.deletedAt IS NULL
             GROUP BY b.id, b.slug, b.nameAr, b.nameEn
             ORDER BY COUNT(p) DESC
            """)
    List<BrandFacet> brandFacets();

    @Query("""
            SELECT b.id AS id, b.slug AS slug, b.nameAr AS nameAr, b.nameEn AS nameEn, COUNT(p) AS total
              FROM Product p JOIN p.brand b
             WHERE p.status = 'ACTIVE' AND p.deletedAt IS NULL
               AND p.primaryCategory.id IN :categoryIds
             GROUP BY b.id, b.slug, b.nameAr, b.nameEn
             ORDER BY COUNT(p) DESC
            """)
    List<BrandFacet> brandFacetsInCategories(@Param("categoryIds") List<UUID> categoryIds);

    @Query("""
            SELECT MIN(p.basePrice) AS minPrice, MAX(p.basePrice) AS maxPrice
              FROM Product p
             WHERE p.status = 'ACTIVE' AND p.deletedAt IS NULL
            """)
    PriceRangeFacet priceRangeFacet();

    @Query("""
            SELECT MIN(p.basePrice) AS minPrice, MAX(p.basePrice) AS maxPrice
              FROM Product p
             WHERE p.status = 'ACTIVE' AND p.deletedAt IS NULL
               AND p.primaryCategory.id IN :categoryIds
            """)
    PriceRangeFacet priceRangeFacetInCategories(@Param("categoryIds") List<UUID> categoryIds);

    /** Projection for the brand filter sidebar. */
    interface BrandFacet {
        UUID getId();

        String getSlug();

        String getNameAr();

        String getNameEn();

        long getTotal();
    }

    interface PriceRangeFacet {
        java.math.BigDecimal getMinPrice();

        java.math.BigDecimal getMaxPrice();
    }
}
