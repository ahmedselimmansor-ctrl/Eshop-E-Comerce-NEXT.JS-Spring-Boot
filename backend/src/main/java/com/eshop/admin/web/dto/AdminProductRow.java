package com.eshop.admin.web.dto;

import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductStatus;
import com.eshop.catalog.domain.ProductType;
import com.eshop.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A row in the admin product list.
 *
 * <p>Separate from {@code ProductCardResponse} rather than an extension of it. The storefront card
 * describes a product a customer may buy, so it has no reason to carry a status — everything it
 * returns is published by definition. This list is the opposite: its whole job is to show drafts and
 * archived products alongside live ones, and folding {@code status} into the shared card would put
 * that field on every public listing response for the sake of one internal screen.
 */
@Schema(name = "AdminProductRow")
public record AdminProductRow(
        UUID id,
        String sku,
        String slug,
        String name,
        String brandName,
        Money price,
        Money compareAtPrice,
        ProductStatus status,
        ProductType type,
        boolean featured,
        String imageUrl,
        int variantCount,

        @Schema(description = "Sellable units across all warehouses; null when not requested")
        Integer available,

        Instant updatedAt
) {

    public static AdminProductRow from(Product product, String locale, Integer available) {
        var image = product.primaryImage().orElse(null);

        return new AdminProductRow(
                product.getId(),
                product.getSku(),
                product.getSlug(),
                product.name(locale),
                product.getBrand() == null ? null : product.getBrand().name(locale),
                product.lowestPrice(),
                product.getCompareAtPrice() == null
                        ? null
                        : Money.of(product.getCompareAtPrice(), product.getCurrency()),
                product.getStatus(),
                product.getType(),
                product.isFeatured(),
                image == null ? null : image.getUrl(),
                product.getVariants().size(),
                available,
                product.getUpdatedAt());
    }
}
