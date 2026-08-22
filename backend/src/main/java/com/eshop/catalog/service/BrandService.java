package com.eshop.catalog.service;

import com.eshop.catalog.domain.Brand;
import com.eshop.catalog.web.dto.BrandResponse;
import com.eshop.catalog.repository.BrandRepository;
import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.common.util.Slugs;
import com.eshop.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrandService {

    private final BrandRepository brands;

    @Cacheable(value = RedisConfig.CACHE_BRANDS, key = "'all'")
    @Transactional(readOnly = true)
    public List<Brand> listActive() {
        return brands.findByActiveTrueOrderByPositionAscNameArAsc();
    }

    @Cacheable(value = RedisConfig.CACHE_BRANDS, key = "'featured'")
    @Transactional(readOnly = true)
    public List<Brand> listFeatured() {
        return brands.findByActiveTrueAndFeaturedTrueOrderByPositionAsc();
    }

    /**
     * One brand, as the API returns it.
     *
     * <p>The mapping lives here rather than in the controller so no detached entity crosses that
     * boundary. See {@code LayeringTest} for why that rule exists.
     */
    @Transactional(readOnly = true)
    public BrandResponse responseBySlug(String slug, String locale) {
        return BrandResponse.from(requireBySlug(slug), locale);
    }

    @Transactional(readOnly = true)
    public Brand requireBySlug(String slug) {
        return brands.findBySlug(slug).orElseThrow(() -> NotFoundException.of("Brand", slug));
    }

    @Transactional(readOnly = true)
    public Brand require(UUID id) {
        return brands.findById(id).orElseThrow(() -> NotFoundException.of("Brand", id));
    }

    @Transactional(readOnly = true)
    public Page<Brand> search(String query, boolean activeOnly, Pageable pageable) {
        return brands.search(query == null || query.isBlank() ? null : query.trim(), activeOnly, pageable);
    }

    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_BRANDS, allEntries = true)
    public Brand create(Brand brand) {
        brand.setSlug(resolveSlug(brand.getSlug(), brand.getNameEn(), brand.getNameAr(), null));
        Brand saved = brands.save(brand);
        log.info("brand_created id={} slug={}", saved.getId(), saved.getSlug());
        return saved;
    }

    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_BRANDS, allEntries = true)
    public Brand update(UUID id, Brand changes) {
        Brand brand = require(id);

        brand.setNameAr(changes.getNameAr());
        brand.setNameEn(changes.getNameEn());
        brand.setDescriptionAr(changes.getDescriptionAr());
        brand.setDescriptionEn(changes.getDescriptionEn());
        brand.setLogoUrl(changes.getLogoUrl());
        brand.setWebsite(changes.getWebsite());
        brand.setActive(changes.isActive());
        brand.setFeatured(changes.isFeatured());
        brand.setPosition(changes.getPosition());

        if (changes.getSlug() != null && !changes.getSlug().equals(brand.getSlug())) {
            brand.setSlug(resolveSlug(changes.getSlug(), changes.getNameEn(), changes.getNameAr(), id));
        }
        return brands.save(brand);
    }

    /**
     * Deactivates rather than deletes.
     *
     * <p>Products carry a foreign key to the brand; removing the row would either null out that
     * reference across the catalog or be rejected outright. Deactivating hides the brand from the
     * storefront while every product keeps its attribution.
     */
    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_BRANDS, allEntries = true)
    public void deactivate(UUID id) {
        Brand brand = require(id);
        brand.setActive(false);
        brands.save(brand);
        log.info("brand_deactivated id={}", id);
    }

    /**
     * Brand slugs prefer the English name - "samsung" reads better in a URL and is what customers
     * type - falling back to the Arabic name when no English name is set.
     */
    private String resolveSlug(String requested, String nameEn, String nameAr, UUID selfId) {
        String base = requested != null && !requested.isBlank()
                ? requested
                : (nameEn != null && !nameEn.isBlank() ? nameEn : nameAr);

        return Slugs.slugifyUnique(base, nameAr, candidate ->
                Slugs.isReserved(candidate)
                        || brands.findBySlug(candidate)
                                .filter(existing -> !existing.getId().equals(selfId))
                                .isPresent());
    }

    /** Guards a slug change or creation that would collide, surfaced as a field-level error. */
    public void requireSlugAvailable(String slug, UUID selfId) {
        brands.findBySlug(slug)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "Brand slug already used")
                            .withViolation("slug", "DUPLICATE", "هذا الرابط مستخدم بالفعل", slug);
                });
    }
}
