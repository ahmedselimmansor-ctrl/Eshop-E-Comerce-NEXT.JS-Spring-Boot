package com.eshop.search.domain;

import com.eshop.catalog.domain.Category;
import com.eshop.catalog.domain.Product;
import com.eshop.common.util.HtmlSanitizer;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Elasticsearch representation of a product.
 *
 * <p>Denormalised on purpose: brand names, category paths, attribute values, and stock are all
 * flattened in so that a filtered listing with facets is one query instead of a join. The cost is
 * that a brand rename requires reindexing its products, which the outbox handles.
 *
 * <p>This is a <em>search</em> structure, not a source of truth. Query results are ids; the cards
 * are hydrated from PostgreSQL, so a price edit is visible immediately even while the index lags.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductDocument(
        String id,
        String sku,
        String slug,

        String nameAr,
        String nameEn,
        String descriptionAr,
        String descriptionEn,

        String brandId,
        String brandSlug,
        String brandNameAr,
        String brandNameEn,

        /** The primary category and every ancestor, so filtering a parent matches its children. */
        List<String> categoryIds,
        List<String> categorySlugs,
        String categoryNameAr,
        String categoryNameEn,

        BigDecimal price,
        BigDecimal compareAtPrice,
        String currency,
        boolean onSale,
        int discountPercent,

        BigDecimal ratingAvg,
        int ratingCount,
        int soldCount,
        long viewCount,

        boolean inStock,
        int availableQuantity,

        String imageUrl,
        String blurhash,
        String status,
        boolean featured,
        Instant publishedAt,
        Instant updatedAt,

        List<AttributeEntry> attributes,

        /** Everything worth matching on, concatenated - SKU, brand, category, searchable specs. */
        String searchKeywords
) {

    public record AttributeEntry(String code, String valueCode, String valueAr, String valueEn) {
    }

    /**
     * @param ancestors  the primary category's ancestors, resolved by the caller
     * @param available  sellable units across all warehouses
     */
    public static ProductDocument from(Product product, List<Category> ancestors, int available) {
        List<String> categoryIds = new ArrayList<>();
        List<String> categorySlugs = new ArrayList<>();

        if (product.getPrimaryCategory() != null) {
            categoryIds.add(product.getPrimaryCategory().getId().toString());
            categorySlugs.add(product.getPrimaryCategory().getSlug());
        }
        ancestors.forEach(ancestor -> {
            categoryIds.add(ancestor.getId().toString());
            categorySlugs.add(ancestor.getSlug());
        });
        product.getCategories().forEach(category -> {
            categoryIds.add(category.getId().toString());
            categorySlugs.add(category.getSlug());
        });

        List<AttributeEntry> attributes = product.getAttributeValues().stream()
                .filter(value -> value.getAttributeValue() != null)
                .map(value -> new AttributeEntry(
                        value.getAttribute().getCode(),
                        value.getAttributeValue().getCode(),
                        value.getAttributeValue().getValueAr(),
                        value.getAttributeValue().getValueEn()))
                .toList();

        return new ProductDocument(
                product.getId().toString(),
                product.getSku(),
                product.getSlug(),
                product.getNameAr(),
                product.getNameEn(),
                // Stripped to plain text: indexing markup pollutes term frequencies and lets a
                // search for "strong" match every bolded description.
                HtmlSanitizer.excerpt(product.getDescriptionAr(), 4000),
                HtmlSanitizer.excerpt(product.getDescriptionEn(), 4000),
                product.getBrand() == null ? null : product.getBrand().getId().toString(),
                product.getBrand() == null ? null : product.getBrand().getSlug(),
                product.getBrand() == null ? null : product.getBrand().getNameAr(),
                product.getBrand() == null ? null : product.getBrand().getNameEn(),
                List.copyOf(new LinkedHashSet<>(categoryIds)),
                List.copyOf(new LinkedHashSet<>(categorySlugs)),
                product.getPrimaryCategory() == null ? null : product.getPrimaryCategory().getNameAr(),
                product.getPrimaryCategory() == null ? null : product.getPrimaryCategory().getNameEn(),
                product.lowestPrice().amount(),
                product.getCompareAtPrice(),
                product.getCurrency(),
                product.isOnSale(),
                product.discountPercent(),
                product.getRatingAvg(),
                product.getRatingCount(),
                product.getSoldCount(),
                product.getViewCount(),
                available > 0,
                available,
                product.primaryImage().map(image -> image.getUrl()).orElse(null),
                product.primaryImage().map(image -> image.getBlurhash()).orElse(null),
                product.getStatus().name(),
                product.isFeatured(),
                product.getPublishedAt(),
                product.getUpdatedAt(),
                attributes,
                buildKeywords(product, attributes));
    }

    /**
     * A catch-all match field.
     *
     * <p>Shoppers search for a SKU, a brand, or a spec ("سامسونج 256 جيجا") as often as for a
     * product name. Concatenating those into one analysed field means a single {@code multi_match}
     * covers all of it without six more clauses.
     */
    private static String buildKeywords(Product product, List<AttributeEntry> attributes) {
        Set<String> parts = new LinkedHashSet<>();
        parts.add(product.getSku());
        parts.add(product.getNameAr());
        parts.add(product.getNameEn());

        if (product.getBrand() != null) {
            parts.add(product.getBrand().getNameAr());
            parts.add(product.getBrand().getNameEn());
        }
        if (product.getPrimaryCategory() != null) {
            parts.add(product.getPrimaryCategory().getNameAr());
            parts.add(product.getPrimaryCategory().getNameEn());
        }
        product.getVariants().forEach(variant -> parts.add(variant.getSku()));
        attributes.forEach(attribute -> {
            parts.add(attribute.valueAr());
            parts.add(attribute.valueEn());
        });

        parts.removeIf(part -> part == null || part.isBlank());
        return String.join(" ", parts);
    }
}
