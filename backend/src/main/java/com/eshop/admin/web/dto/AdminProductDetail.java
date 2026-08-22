package com.eshop.admin.web.dto;

import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductStatus;
import com.eshop.catalog.domain.ProductType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A product as the editor sees it: raw, per-language, and unresolved.
 *
 * <p>The storefront's {@code ProductDetailResponse} returns one {@code name} and one
 * {@code description}, already resolved for the visitor's locale — which is exactly right for a
 * product page and exactly wrong for an edit form. Prefilling both language fields from a single
 * resolved string would silently copy Arabic over English (or the reverse) the first time anyone
 * pressed save. This returns every field as it is stored, so the form round-trips losslessly.
 */
@Schema(name = "AdminProductDetail")
public record AdminProductDetail(
        UUID id,
        String sku,
        String slug,

        String nameAr,
        String nameEn,
        String shortDescriptionAr,
        String shortDescriptionEn,
        String descriptionAr,
        String descriptionEn,

        UUID brandId,
        String brandName,
        UUID primaryCategoryId,
        String primaryCategoryName,
        List<UUID> categoryIds,

        BigDecimal basePrice,
        BigDecimal compareAtPrice,
        BigDecimal costPrice,
        String currency,
        BigDecimal taxRate,

        Integer weightGrams,
        Integer lengthMm,
        Integer widthMm,
        Integer heightMm,

        ProductStatus status,
        ProductType type,
        boolean featured,
        boolean digital,
        boolean requiresShipping,

        String seoTitleAr,
        String seoTitleEn,
        String seoDescriptionAr,
        String seoDescriptionEn,

        List<Image> images,
        List<Variant> variants,
        List<UUID> attributeValueIds,

        Instant publishedAt,
        Instant updatedAt
) {

    @Schema(name = "AdminProductImage")
    public record Image(UUID id, String url, String objectKey, String altAr, String altEn,
                        Integer width, Integer height, String blurhash, boolean primary,
                        int position) {
    }

    @Schema(name = "AdminProductVariant")
    public record Variant(UUID id, String sku, String barcode, BigDecimal price,
                          BigDecimal compareAtPrice, BigDecimal costPrice, Integer weightGrams,
                          String imageUrl, int position, boolean active,
                          List<UUID> attributeValueIds, String label) {
    }

    public static AdminProductDetail from(Product product) {
        return new AdminProductDetail(
                product.getId(),
                product.getSku(),
                product.getSlug(),

                product.getNameAr(),
                product.getNameEn(),
                product.getShortDescriptionAr(),
                product.getShortDescriptionEn(),
                product.getDescriptionAr(),
                product.getDescriptionEn(),

                product.getBrand() == null ? null : product.getBrand().getId(),
                product.getBrand() == null ? null : product.getBrand().getNameEn(),
                product.getPrimaryCategory() == null ? null : product.getPrimaryCategory().getId(),
                product.getPrimaryCategory() == null ? null : product.getPrimaryCategory().getNameEn(),
                product.getCategories().stream().map(category -> category.getId()).toList(),

                product.getBasePrice(),
                product.getCompareAtPrice(),
                product.getCostPrice(),
                product.getCurrency(),
                product.getTaxRate(),

                product.getWeightGrams(),
                product.getLengthMm(),
                product.getWidthMm(),
                product.getHeightMm(),

                product.getStatus(),
                product.getType(),
                product.isFeatured(),
                product.isDigital(),
                product.isRequiresShipping(),

                product.getSeoTitleAr(),
                product.getSeoTitleEn(),
                product.getSeoDescriptionAr(),
                product.getSeoDescriptionEn(),

                product.getImages().stream()
                        .map(image -> new Image(
                                image.getId(), image.getUrl(), image.getObjectKey(),
                                image.getAltAr(), image.getAltEn(),
                                image.getWidth(), image.getHeight(), image.getBlurhash(),
                                image.isPrimary(), image.getPosition()))
                        .toList(),

                product.getVariants().stream()
                        .map(variant -> new Variant(
                                variant.getId(), variant.getSku(), variant.getBarcode(),
                                variant.getPrice(), variant.getCompareAtPrice(),
                                variant.getCostPrice(), variant.getWeightGrams(),
                                variant.getImageUrl(), variant.getPosition(), variant.isActive(),
                                variant.getAttributeValues().stream()
                                        .map(link -> link.getAttributeValue().getId())
                                        .toList(),
                                variant.label("en")))
                        .toList(),

                product.getAttributeValues().stream()
                        .filter(link -> link.getAttributeValue() != null)
                        .map(link -> link.getAttributeValue().getId())
                        .toList(),

                product.getPublishedAt(),
                product.getUpdatedAt());
    }
}
