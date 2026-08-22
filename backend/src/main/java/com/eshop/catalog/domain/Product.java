package com.eshop.catalog.domain;

import com.eshop.common.domain.AuditableEntity;
import com.eshop.common.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A catalog item.
 *
 * <p>Every association is {@code LAZY}. A product listing renders twenty of these per page and
 * needs only the name, price, and primary image; an eager {@code variants} collection would turn
 * one query into twenty-one. The read paths that genuinely need the graph ask for it explicitly via
 * {@code @EntityGraph} on the repository method.
 *
 * <p>PostgreSQL is the source of truth. Elasticsearch mirrors these rows for search and faceting
 * and is rebuilt from here - never the other way round.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product extends AuditableEntity {

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "slug", nullable = false, length = 180)
    private String slug;

    @Column(name = "name_ar", nullable = false)
    private String nameAr;

    @Column(name = "name_en")
    private String nameEn;

    @Column(name = "short_description_ar", length = 600)
    private String shortDescriptionAr;

    @Column(name = "short_description_en", length = 600)
    private String shortDescriptionEn;

    /** Rich text. Sanitised through {@code HtmlSanitizer} before persistence - never trusted. */
    @Column(name = "description_ar")
    private String descriptionAr;

    @Column(name = "description_en")
    private String descriptionEn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_category_id")
    private Category primaryCategory;

    /** Additional placements; the primary category is what breadcrumbs and canonical URLs use. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "product_categories",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    @BatchSize(size = 50)
    private Set<Category> categories = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status = ProductStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ProductType type = ProductType.SIMPLE;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    /** Original price shown struck through. Enforced {@code >= basePrice} by a check constraint. */
    @Column(name = "compare_at_price", precision = 12, scale = 2)
    private BigDecimal compareAtPrice;

    /** Never exposed on any storefront endpoint - margin data. */
    @Column(name = "cost_price", precision = 12, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = Money.DEFAULT_CURRENCY;

    @Column(name = "tax_class", nullable = false, length = 20)
    private String taxClass = "STANDARD";

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate = new BigDecimal("0.1400");

    @Column(name = "weight_grams")
    private Integer weightGrams;

    @Column(name = "length_mm")
    private Integer lengthMm;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(name = "is_featured", nullable = false)
    private boolean featured = false;

    @Column(name = "is_digital", nullable = false)
    private boolean digital = false;

    @Column(name = "requires_shipping", nullable = false)
    private boolean requiresShipping = true;

    /** Denormalised from approved reviews by the {@code trg_reviews_rating_rollup} trigger. */
    @Column(name = "rating_avg", nullable = false, precision = 2, scale = 1)
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount = 0;

    @Column(name = "view_count", nullable = false)
    private long viewCount = 0;

    @Column(name = "sold_count", nullable = false)
    private int soldCount = 0;

    @Column(name = "seo_title_ar", length = 200)
    private String seoTitleAr;

    @Column(name = "seo_title_en", length = 200)
    private String seoTitleEn;

    @Column(name = "seo_description_ar", length = 400)
    private String seoDescriptionAr;

    @Column(name = "seo_description_en", length = 400)
    private String seoDescriptionEn;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /*
     * A Set, not a List, and batch-loaded rather than join-fetched.
     *
     * Two Hibernate constraints shape this. It refuses to fetch more than one *bag* (an unindexed
     * List) per query, and the product-card entity graphs need this collection alongside another
     * one - hence the Set, which keeps its order because @OrderBy makes Hibernate use a
     * LinkedHashSet. And it cannot page in SQL while a collection is join-fetched, so the listing
     * queries leave this out of their graph entirely; @BatchSize then loads a whole page's worth in
     * one extra query instead of one per row.
     */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private Set<ProductVariant> variants = new LinkedHashSet<>();

    /*
     * A Set, not a List, and batch-loaded rather than join-fetched.
     *
     * Two Hibernate constraints shape this. It refuses to fetch more than one *bag* (an unindexed
     * List) per query, and the product-card entity graphs need this collection alongside another
     * one - hence the Set, which keeps its order because @OrderBy makes Hibernate use a
     * LinkedHashSet. And it cannot page in SQL while a collection is join-fetched, so the listing
     * queries leave this out of their graph entirely; @BatchSize then loads a whole page's worth in
     * one extra query instead of one per row.
     */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private Set<ProductImage> images = new LinkedHashSet<>();

    /*
     * Left out of the paged entity graphs - a join-fetched collection forces Hibernate to page in
     * memory. @BatchSize loads a whole page's worth in one extra query instead of one per row.
     */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    private List<ProductAttributeValue> attributeValues = new ArrayList<>();

    // ------------------------------------------------------------- behaviour --

    public String name(String locale) {
        return "en".equalsIgnoreCase(locale) && nameEn != null && !nameEn.isBlank() ? nameEn : nameAr;
    }

    public boolean isPurchasable() {
        return status == ProductStatus.ACTIVE && deletedAt == null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isOnSale() {
        return compareAtPrice != null && compareAtPrice.compareTo(basePrice) > 0;
    }

    /** Whole-percent discount for the badge, or {@code 0} when not discounted. */
    public int discountPercent() {
        if (!isOnSale()) {
            return 0;
        }
        return compareAtPrice.subtract(basePrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(compareAtPrice, 0, java.math.RoundingMode.DOWN)
                .intValue();
    }

    public Money price() {
        return Money.of(basePrice, currency);
    }

    /**
     * Lowest active variant price, used for the "from …" label on a variable product.
     * Falls back to the base price when no variant is active.
     */
    public Money lowestPrice() {
        if (type == ProductType.SIMPLE || variants.isEmpty()) {
            return price();
        }
        return variants.stream()
                .filter(ProductVariant::isActive)
                .map(variant -> Money.of(variant.getPrice(), currency))
                .min(Money::compareTo)
                .orElseGet(this::price);
    }

    public Optional<ProductImage> primaryImage() {
        return images.stream()
                .filter(ProductImage::isPrimary)
                .findFirst()
                .or(() -> images.stream().findFirst());
    }

    public Optional<ProductVariant> findVariant(java.util.UUID variantId) {
        return variants.stream().filter(variant -> variant.getId().equals(variantId)).findFirst();
    }

    public void addVariant(ProductVariant variant) {
        variant.setProduct(this);
        variants.add(variant);
    }

    public void addImage(ProductImage image) {
        image.setProduct(this);
        images.add(image);
    }

    /**
     * Moves the product to {@code ACTIVE}, stamping the publication time the first time only so
     * that "newest arrivals" ordering is not reshuffled by an unrelated later edit.
     */
    public void publish() {
        status = ProductStatus.ACTIVE;
        if (publishedAt == null) {
            publishedAt = Instant.now();
        }
    }
}
