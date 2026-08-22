package com.eshop.catalog.web.dto;

import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductImage;
import com.eshop.catalog.domain.ProductStatus;
import com.eshop.catalog.domain.ProductType;
import com.eshop.catalog.domain.ProductVariant;
import com.eshop.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything the product detail page renders, in one response. */
@Schema(name = "ProductDetailResponse")
public record ProductDetailResponse(
        UUID id,
        String slug,
        String sku,
        String name,
        String shortDescription,

        @Schema(description = "Sanitised HTML")
        String description,

        ProductStatus status,
        ProductType type,
        Money price,
        Money compareAtPrice,
        int discountPercent,
        String currency,
        BigDecimal taxRate,
        Integer weightGrams,
        boolean requiresShipping,
        boolean digital,
        BigDecimal ratingAvg,
        int ratingCount,
        int soldCount,
        Instant publishedAt,

        BrandResponse brand,
        CategoryResponse category,

        @Schema(description = "Ancestors of the primary category, root first, for breadcrumbs")
        List<CategoryResponse> breadcrumb,

        List<ImageResponse> images,

        @Schema(description = "Attribute axes that generate variants, in display order")
        List<VariantAxis> variantAxes,

        List<VariantResponse> variants,

        @Schema(description = "Descriptive specifications for the spec table")
        List<SpecResponse> specifications,

        @Schema(description = "Sellable units across all warehouses; for a variable product, the total")
        Integer availableQuantity,

        boolean inStock,
        SeoMeta seo
) {

    public record ImageResponse(UUID id, String url, String alt, boolean primary,
                                Integer width, Integer height, String blurhash, UUID variantId) {
    }

    /** One selectable dimension - "Colour" with its available values. */
    public record VariantAxis(String code, String name, String type, List<AxisValue> values) {
    }

    public record AxisValue(UUID id, String code, String value, String hexColor, String imageUrl) {
    }

    public record VariantResponse(UUID id, String sku, Money price, Money compareAtPrice,
                                  int discountPercent, String label, String imageUrl,
                                  boolean active, Integer availableQuantity, boolean inStock,

                                  @Schema(description = "attributeCode → attributeValueCode; how the "
                                          + "frontend maps a selection to a variant")
                                  Map<String, String> selection) {
    }

    public record SpecResponse(String code, String name, String value, String unit) {
    }

    public record SeoMeta(String title, String description) {
    }

    /**
     * @param availability sellable units keyed by variant id, plus the product id itself for a
     *                     simple product. Supplied by the caller because stock is owned by the
     *                     inventory domain, not the catalog.
     */
    public static ProductDetailResponse from(Product product,
                                             String locale,
                                             List<com.eshop.catalog.domain.Category> ancestors,
                                             Map<UUID, Integer> availability) {
        boolean english = "en".equalsIgnoreCase(locale);

        List<ImageResponse> images = product.getImages().stream()
                .sorted(Comparator.comparingInt(ProductImage::getPosition))
                .map(image -> new ImageResponse(
                        image.getId(), image.getUrl(), image.alt(locale), image.isPrimary(),
                        image.getWidth(), image.getHeight(), image.getBlurhash(),
                        image.getVariant() == null ? null : image.getVariant().getId()))
                .toList();

        List<VariantResponse> variants = product.getVariants().stream()
                .sorted(Comparator.comparingInt(ProductVariant::getPosition))
                .map(variant -> toVariant(variant, product, locale, availability))
                .toList();

        int totalAvailable = product.getType() == ProductType.SIMPLE
                ? availability.getOrDefault(product.getId(), 0)
                : variants.stream()
                        .mapToInt(variant -> variant.availableQuantity() == null ? 0 : variant.availableQuantity())
                        .sum();

        List<SpecResponse> specifications = product.getAttributeValues().stream()
                .filter(value -> !value.getAttribute().isVariantAxis())
                .sorted(Comparator.comparingInt(value -> value.getAttribute().getPosition()))
                .map(value -> new SpecResponse(
                        value.getAttribute().getCode(),
                        value.getAttribute().name(locale),
                        value.display(locale),
                        value.getAttribute().getUnit()))
                .toList();

        return new ProductDetailResponse(
                product.getId(),
                product.getSlug(),
                product.getSku(),
                product.name(locale),
                english ? product.getShortDescriptionEn() : product.getShortDescriptionAr(),
                english ? product.getDescriptionEn() : product.getDescriptionAr(),
                product.getStatus(),
                product.getType(),
                product.lowestPrice(),
                product.getCompareAtPrice() == null
                        ? null : Money.of(product.getCompareAtPrice(), product.getCurrency()),
                product.discountPercent(),
                product.getCurrency(),
                product.getTaxRate(),
                product.getWeightGrams(),
                product.isRequiresShipping(),
                product.isDigital(),
                product.getRatingAvg(),
                product.getRatingCount(),
                product.getSoldCount(),
                product.getPublishedAt(),
                product.getBrand() == null ? null : BrandResponse.from(product.getBrand(), locale),
                product.getPrimaryCategory() == null
                        ? null : CategoryResponse.flat(product.getPrimaryCategory(), locale),
                ancestors.stream().map(category -> CategoryResponse.flat(category, locale)).toList(),
                images,
                buildAxes(product, locale),
                variants,
                specifications,
                totalAvailable,
                totalAvailable > 0,
                new SeoMeta(
                        english ? product.getSeoTitleEn() : product.getSeoTitleAr(),
                        english ? product.getSeoDescriptionEn() : product.getSeoDescriptionAr()));
    }

    private static VariantResponse toVariant(ProductVariant variant,
                                             Product product,
                                             String locale,
                                             Map<UUID, Integer> availability) {
        Map<String, String> selection = new LinkedHashMap<>();
        variant.getAttributeValues().forEach(value ->
                selection.put(value.getAttribute().getCode(), value.getAttributeValue().getCode()));

        int available = availability.getOrDefault(variant.getId(), 0);
        int discountPercent = variant.isOnSale()
                ? variant.getCompareAtPrice().subtract(variant.getPrice())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(variant.getCompareAtPrice(), 0, java.math.RoundingMode.DOWN)
                        .intValue()
                : 0;

        return new VariantResponse(
                variant.getId(),
                variant.getSku(),
                Money.of(variant.getPrice(), product.getCurrency()),
                variant.getCompareAtPrice() == null
                        ? null : Money.of(variant.getCompareAtPrice(), product.getCurrency()),
                discountPercent,
                variant.label(locale),
                variant.getImageUrl(),
                variant.isActive(),
                available,
                available > 0,
                selection);
    }

    /**
     * Derives the selectable axes from the variants themselves rather than from the attribute
     * definitions, so a colour with no variant in stock configuration never appears as an option
     * that leads nowhere.
     */
    private static List<VariantAxis> buildAxes(Product product, String locale) {
        Map<String, VariantAxis> axes = new LinkedHashMap<>();
        Map<String, Map<String, AxisValue>> valuesByAxis = new LinkedHashMap<>();

        product.getVariants().stream()
                .filter(ProductVariant::isActive)
                .sorted(Comparator.comparingInt(ProductVariant::getPosition))
                .forEach(variant -> variant.getAttributeValues().forEach(link -> {
                    var attribute = link.getAttribute();
                    var value = link.getAttributeValue();

                    valuesByAxis
                            .computeIfAbsent(attribute.getCode(), key -> new LinkedHashMap<>())
                            .putIfAbsent(value.getCode(), new AxisValue(
                                    value.getId(), value.getCode(), value.value(locale),
                                    value.getHexColor(), value.getImageUrl()));

                    axes.putIfAbsent(attribute.getCode(), new VariantAxis(
                            attribute.getCode(), attribute.name(locale),
                            attribute.getType().name(), List.of()));
                }));

        return axes.values().stream()
                .map(axis -> new VariantAxis(axis.code(), axis.name(), axis.type(),
                        List.copyOf(valuesByAxis.getOrDefault(axis.code(), Map.of()).values())))
                .toList();
    }
}
