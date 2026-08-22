package com.eshop.catalog.web.dto;

import com.eshop.catalog.domain.Product;
import com.eshop.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The listing projection — everything a product card renders, and nothing else.
 *
 * <p>Kept deliberately small. A category page returns twenty of these, and shipping full
 * descriptions, variant lists, or specification tables that the card never displays would multiply
 * the payload for no visible benefit. The detail endpoint serves the rest.
 */
@Schema(name = "ProductCardResponse")
public record ProductCardResponse(
        UUID id,
        String slug,
        String sku,
        String name,
        String shortDescription,
        Money price,

        @Schema(description = "Original price when discounted; null otherwise")
        Money compareAtPrice,

        @Schema(description = "Whole-percent discount, 0 when not on sale")
        int discountPercent,

        String imageUrl,
        String imageBlurhash,
        String brandName,
        String brandSlug,
        BigDecimal ratingAvg,
        int ratingCount,
        boolean featured,

        @Schema(description = "Populated only where the caller asked for availability; null otherwise")
        Boolean inStock
) {

    public static ProductCardResponse from(Product product, String locale) {
        return from(product, locale, null);
    }

    public static ProductCardResponse from(Product product, String locale, Boolean inStock) {
        boolean english = "en".equalsIgnoreCase(locale);
        var image = product.primaryImage().orElse(null);

        return new ProductCardResponse(
                product.getId(),
                product.getSlug(),
                product.getSku(),
                product.name(locale),
                english ? product.getShortDescriptionEn() : product.getShortDescriptionAr(),
                // Variable products advertise their cheapest variant, matching the "from …" label.
                product.lowestPrice(),
                product.getCompareAtPrice() == null
                        ? null
                        : Money.of(product.getCompareAtPrice(), product.getCurrency()),
                product.discountPercent(),
                image == null ? null : image.getUrl(),
                image == null ? null : image.getBlurhash(),
                product.getBrand() == null ? null : product.getBrand().name(locale),
                product.getBrand() == null ? null : product.getBrand().getSlug(),
                product.getRatingAvg(),
                product.getRatingCount(),
                product.isFeatured(),
                inStock);
    }

    public static List<ProductCardResponse> from(List<Product> products, String locale) {
        return products.stream().map(product -> from(product, locale)).toList();
    }
}
