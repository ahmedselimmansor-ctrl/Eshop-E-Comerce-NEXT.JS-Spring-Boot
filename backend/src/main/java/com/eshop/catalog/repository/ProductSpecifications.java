package com.eshop.catalog.repository;

import com.eshop.catalog.domain.Attribute;
import com.eshop.catalog.domain.AttributeValue;
import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductAttributeValue;
import com.eshop.catalog.domain.ProductStatus;
import com.eshop.inventory.domain.InventoryItem;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Composable predicates for the product listing.
 *
 * <p>Each factory returns a {@link Specification} that is a no-op when its input is absent, so a
 * caller can chain every filter unconditionally and let the ones that were not supplied fall away.
 * That keeps the query-building code free of nested {@code if} blocks and makes it obvious which
 * filters exist.
 */
public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    /** Always applied on storefront queries: published, not soft-deleted. */
    public static Specification<Product> visible() {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), ProductStatus.ACTIVE),
                cb.isNull(root.get("deletedAt")));
    }

    public static Specification<Product> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    /**
     * Category filter over a pre-expanded id set.
     *
     * <p>The caller passes the category and all of its descendants (resolved with one prefix scan on
     * the materialised path), so "Electronics" also returns everything filed under Phones. Both the
     * primary category and the secondary {@code product_categories} placements are matched.
     */
    public static Specification<Product> inCategories(Collection<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            // A collection join would multiply rows; DISTINCT keeps the page size honest.
            query.distinct(true);
            return cb.or(
                    root.get("primaryCategory").get("id").in(categoryIds),
                    root.join("categories", JoinType.LEFT).get("id").in(categoryIds));
        };
    }

    public static Specification<Product> brandSlugIn(Collection<String> brandSlugs) {
        if (brandSlugs == null || brandSlugs.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.join("brand", JoinType.INNER).get("slug").in(brandSlugs);
    }

    public static Specification<Product> priceBetween(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return null;
        }
        return (root, query, cb) -> {
            Expression<BigDecimal> price = root.get("basePrice");
            if (min != null && max != null) {
                return cb.between(price, min, max);
            }
            return min != null
                    ? cb.greaterThanOrEqualTo(price, min)
                    : cb.lessThanOrEqualTo(price, max);
        };
    }

    public static Specification<Product> minRating(BigDecimal minRating) {
        if (minRating == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("ratingAvg"), minRating);
    }

    /** Products whose {@code compareAtPrice} is above the selling price. */
    public static Specification<Product> onSale(Boolean onSale) {
        if (!Boolean.TRUE.equals(onSale)) {
            return null;
        }
        return (root, query, cb) -> cb.and(
                cb.isNotNull(root.get("compareAtPrice")),
                cb.greaterThan(root.get("compareAtPrice"), root.get("basePrice")));
    }

    /**
     * Attribute facets, expressed as {@code attributeCode → allowed value codes}.
     *
     * <p>Semantics deliberately differ per axis, and this is the part filter UIs usually get wrong:
     * values <em>within</em> one attribute are OR-ed (red or blue), while <em>different</em>
     * attributes are AND-ed (red or blue, <b>and</b> size large). Selecting two colours should widen
     * the result set; adding a size should narrow it.
     *
     * <p>Implemented as one correlated EXISTS per attribute rather than one join per attribute - a
     * join chain would multiply rows and force a DISTINCT over the whole result set.
     */
    public static Specification<Product> withAttributes(Map<String, List<String>> attributeFilters) {
        if (attributeFilters == null || attributeFilters.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            Predicate combined = cb.conjunction();

            for (Map.Entry<String, List<String>> filter : attributeFilters.entrySet()) {
                String attributeCode = filter.getKey();
                List<String> valueCodes = filter.getValue();
                if (valueCodes == null || valueCodes.isEmpty()) {
                    continue;
                }

                Subquery<UUID> exists = query.subquery(UUID.class);
                Root<ProductAttributeValue> pav = exists.from(ProductAttributeValue.class);
                jakarta.persistence.criteria.Join<ProductAttributeValue, Attribute> attribute =
                        pav.join("attribute");
                jakarta.persistence.criteria.Join<ProductAttributeValue, AttributeValue> value =
                        pav.join("attributeValue");

                exists.select(pav.get("id"))
                        .where(cb.and(
                                cb.equal(pav.get("product").get("id"), root.get("id")),
                                cb.equal(attribute.get("code"), attributeCode),
                                value.get("code").in(valueCodes)));

                combined = cb.and(combined, cb.exists(exists));
            }
            return combined;
        };
    }

    /**
     * Products with at least one unit available somewhere.
     *
     * <p>"Available" is {@code quantity_on_hand - quantity_reserved}, never {@code on_hand} alone:
     * units already held for an unpaid order are not sellable, and treating them as such is how a
     * catalog oversells during a flash sale.
     *
     * <p>This reads the inventory table from a catalog query. That is a deliberate read-model
     * exception to the domain-isolation rule - the alternative is materialising every in-stock
     * product id in memory. Writes to inventory still go exclusively through {@code InventoryService}.
     */
    public static Specification<Product> inStock(Boolean inStock) {
        if (!Boolean.TRUE.equals(inStock)) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<UUID> available = query.subquery(UUID.class);
            Root<InventoryItem> item = available.from(InventoryItem.class);

            available.select(item.get("id"))
                    .where(cb.and(
                            cb.equal(item.get("product").get("id"), root.get("id")),
                            cb.greaterThan(
                                    cb.diff(item.<Integer>get("quantityOnHand"),
                                            item.<Integer>get("quantityReserved")),
                                    0)));

            return cb.exists(available);
        };
    }

    /**
     * Trigram-friendly name match, used only when Elasticsearch is unavailable.
     * Full-text relevance ranking lives in the search index, not here.
     */
    public static Specification<Product> nameContains(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String pattern = "%" + term.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("nameAr")), pattern),
                cb.like(cb.lower(root.get("nameEn")), pattern),
                cb.like(cb.lower(root.get("sku")), pattern));
    }
}
