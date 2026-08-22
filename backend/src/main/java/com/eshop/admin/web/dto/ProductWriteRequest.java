package com.eshop.admin.web.dto;

import com.eshop.catalog.domain.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Create or replace a product.
 *
 * <p>A full replacement rather than a patch: the admin form always submits the complete product, and
 * a partial update would make "the customer removed the third image" indistinguishable from "the
 * client did not send the images field".
 */
@Schema(name = "ProductWriteRequest")
public record ProductWriteRequest(

        @NotBlank @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "كود المنتج يقبل حروفاً وأرقاماً و . _ - فقط")
        String sku,

        @Schema(description = "Generated from the name when omitted; uniqueness is resolved server-side")
        @Size(max = 180)
        String slug,

        @NotBlank @Size(max = 255)
        String nameAr,

        @Size(max = 255)
        String nameEn,

        @Size(max = 600) String shortDescriptionAr,
        @Size(max = 600) String shortDescriptionEn,

        @Schema(description = "Rich text; sanitised server-side before storage")
        String descriptionAr,
        String descriptionEn,

        UUID brandId,
        UUID primaryCategoryId,

        @Schema(description = "Secondary placements; the primary category drives breadcrumbs and canonical URLs")
        List<UUID> categoryIds,

        @NotNull @DecimalMin("0.00")
        BigDecimal basePrice,

        @Schema(description = "Struck-through price. Must be at least basePrice — enforced by a check constraint.")
        @DecimalMin("0.00")
        BigDecimal compareAtPrice,

        @Schema(description = "Never exposed on any storefront endpoint")
        @DecimalMin("0.00")
        BigDecimal costPrice,

        @Pattern(regexp = "^[A-Z]{3}$")
        String currency,

        @DecimalMin("0.0000")
        BigDecimal taxRate,

        Integer weightGrams,
        Integer lengthMm,
        Integer widthMm,
        Integer heightMm,

        boolean featured,

        @Schema(description = "Digital products skip shipping entirely")
        boolean digital,

        @NotNull
        ProductStatus status,

        @Size(max = 200) String seoTitleAr,
        @Size(max = 200) String seoTitleEn,
        @Size(max = 400) String seoDescriptionAr,
        @Size(max = 400) String seoDescriptionEn,

        @Schema(description = "Ordered; the first is primary unless one is explicitly flagged")
        @Valid List<ImageSpec> images,

        @Schema(description = "Descriptive specifications — not variant axes")
        List<UUID> attributeValueIds
) {

    public record ImageSpec(
            @NotBlank @Size(max = 1024) String url,
            @Size(max = 512) String objectKey,
            @Size(max = 255) String altAr,
            @Size(max = 255) String altEn,
            Integer width,
            Integer height,
            @Size(max = 64) String blurhash,
            boolean primary
    ) {
    }

    /** One purchasable combination. {@code attributeValueIds} must all be variant-axis values. */
    public record VariantSpec(
            @NotBlank @Size(max = 64) String sku,
            @Size(max = 64) String barcode,
            @NotNull @DecimalMin("0.00") BigDecimal price,
            @DecimalMin("0.00") BigDecimal compareAtPrice,
            @DecimalMin("0.00") BigDecimal costPrice,
            Integer weightGrams,
            @Size(max = 1024) String imageUrl,
            int position,
            boolean active,
            @NotNull List<UUID> attributeValueIds
    ) {
    }
}
