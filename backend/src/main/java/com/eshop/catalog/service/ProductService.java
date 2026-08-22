package com.eshop.catalog.service;

import com.eshop.catalog.domain.Category;
import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductStatus;
import com.eshop.catalog.domain.ProductVariant;
import com.eshop.catalog.repository.ProductRepository;
import com.eshop.catalog.repository.ProductVariantRepository;
import com.eshop.catalog.web.dto.ProductDetailResponse;
import com.eshop.common.exception.NotFoundException;
import com.eshop.config.RedisConfig;
import com.eshop.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Product reads for the storefront: detail pages, curated shelves, and recommendations.
 * Administrative writes live in {@code AdminProductService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryService categories;
    private final InventoryService inventory;

    /**
     * Full detail for the product page.
     *
     * <p>Not cached: the response embeds live stock, and a cached copy would keep advertising a
     * sold-out variant as available for the whole TTL. The single {@code @EntityGraph} query behind
     * it is cheap enough that the cache would buy little anyway.
     */
    @Transactional(readOnly = true)
    public ProductDetailResponse detailBySlug(String slug, String locale) {
        Product product = products.findDetailBySlug(slug)
                .filter(Product::isPurchasable)
                .orElseThrow(() -> NotFoundException.of("Product", slug));

        return buildDetail(product, locale);
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse detailById(UUID id, String locale) {
        Product product = products.findDetailById(id)
                .orElseThrow(() -> NotFoundException.of("Product", id));
        return buildDetail(product, locale);
    }

    private ProductDetailResponse buildDetail(Product product, String locale) {
        List<Category> ancestors = product.getPrimaryCategory() == null
                ? List.of()
                : categories.ancestorsOf(product.getPrimaryCategory());

        return ProductDetailResponse.from(product, locale, ancestors, availabilityOf(product));
    }

    /**
     * Sellable units keyed by variant id, plus the product id for a simple product — the shape
     * {@link ProductDetailResponse} expects.
     */
    private Map<UUID, Integer> availabilityOf(Product product) {
        Map<UUID, Integer> availability = new HashMap<>();

        if (product.getVariants().isEmpty()) {
            availability.put(product.getId(), inventory.available(product.getId(), null));
            return availability;
        }
        for (ProductVariant variant : product.getVariants()) {
            availability.put(variant.getId(),
                    inventory.available(product.getId(), variant.getId()));
        }
        return availability;
    }

    @Transactional(readOnly = true)
    public Product require(UUID id) {
        return products.findById(id)
                .filter(product -> !product.isDeleted())
                .orElseThrow(() -> NotFoundException.of("Product", id));
    }

    @Transactional(readOnly = true)
    public ProductVariant requireVariant(UUID variantId) {
        return variants.findById(variantId)
                .orElseThrow(() -> NotFoundException.of("ProductVariant", variantId));
    }

    /**
     * Loads the images a product card renders, while the session is still open.
     *
     * <p>Listing queries deliberately leave {@code images} out of their entity graph — a join-fetched
     * collection stops Hibernate paging in SQL. With {@code open-in-view} off, the collection has to
     * be touched here or {@code ProductCardResponse.from} hits a detached proxy. {@code @BatchSize}
     * turns this into one extra query for the whole page rather than one per product.
     */
    private static List<Product> withCardImages(List<Product> page) {
        page.forEach(product -> product.getImages().size());
        return page;
    }

    // ------------------------------------------------------- curated shelves --

    @Cacheable(value = RedisConfig.CACHE_PRODUCT_LIST, key = "'featured:' + #limit")
    @Transactional(readOnly = true)
    public List<Product> featured(int limit) {
        return withCardImages(products.findFeatured(PageRequest.of(0, limit)));
    }

    @Cacheable(value = RedisConfig.CACHE_PRODUCT_LIST, key = "'new:' + #limit")
    @Transactional(readOnly = true)
    public List<Product> newArrivals(int limit) {
        return withCardImages(products.findNewArrivals(PageRequest.of(0, limit)));
    }

    @Cacheable(value = RedisConfig.CACHE_PRODUCT_LIST, key = "'best:' + #limit")
    @Transactional(readOnly = true)
    public List<Product> bestSellers(int limit) {
        return withCardImages(products.findBestSellers(PageRequest.of(0, limit)));
    }

    @Transactional(readOnly = true)
    public List<Product> related(UUID productId, int limit) {
        Product product = require(productId);
        UUID categoryId = product.getPrimaryCategory() == null ? null : product.getPrimaryCategory().getId();
        UUID brandId = product.getBrand() == null ? null : product.getBrand().getId();

        if (categoryId == null && brandId == null) {
            return List.of();
        }
        return withCardImages(products.findRelated(productId, categoryId, brandId, PageRequest.of(0, limit)));
    }

    /** "Customers also bought" — derived from real order co-occurrence, not from category overlap. */
    @Transactional(readOnly = true)
    public List<Product> frequentlyBoughtTogether(UUID productId, int limit) {
        List<UUID> ids = products.findFrequentlyBoughtTogether(productId, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Product> byId = new HashMap<>();
        products.findCardsByIds(ids).forEach(product -> byId.put(product.getId(), product));

        List<Product> ordered = new ArrayList<>(ids.size());
        ids.forEach(id -> {
            Product product = byId.get(id);
            if (product != null) {
                ordered.add(product);
            }
        });
        return ordered;
    }

    /**
     * Bumps the view counter.
     *
     * <p>Runs in its own transaction so a failure here can never roll back the page render, and
     * uses a bare UPDATE so it does not touch the product's optimistic-lock version — a page view
     * must not make a concurrent admin save fail.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordView(UUID productId) {
        try {
            products.incrementViewCount(productId);
        } catch (RuntimeException e) {
            log.debug("view_count_increment_failed product={} error={}", productId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return products.countByStatus(ProductStatus.ACTIVE);
    }

    /** Clears the curated-shelf caches after a catalog write. */
    @CacheEvict(value = {RedisConfig.CACHE_PRODUCT_LIST, RedisConfig.CACHE_PRODUCT_DETAIL}, allEntries = true)
    public void evictListingCaches() {
        // Body intentionally empty — the annotation is the behaviour.
    }
}
