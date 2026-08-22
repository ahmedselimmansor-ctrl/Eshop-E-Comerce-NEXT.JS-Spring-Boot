package com.eshop.catalog.web.dto;

import com.eshop.catalog.domain.Brand;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(name = "BrandResponse")
public record BrandResponse(
        UUID id,
        String slug,
        String name,
        String description,
        String logoUrl,
        String website,
        boolean featured
) {

    public static BrandResponse from(Brand brand, String locale) {
        boolean english = "en".equalsIgnoreCase(locale);
        return new BrandResponse(
                brand.getId(),
                brand.getSlug(),
                brand.name(locale),
                english ? brand.getDescriptionEn() : brand.getDescriptionAr(),
                brand.getLogoUrl(),
                brand.getWebsite(),
                brand.isFeatured());
    }

    public static List<BrandResponse> from(List<Brand> brands, String locale) {
        return brands.stream().map(brand -> from(brand, locale)).toList();
    }
}
