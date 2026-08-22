package com.eshop.catalog.domain;

import com.eshop.common.domain.AuditableEntity;
import com.eshop.common.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One purchasable combination of a {@link ProductType#VARIABLE} product - "Red / Large".
 *
 * <p>The variant, not the product, is what a cart line and an order line point at, because price,
 * SKU, barcode, weight, and stock all differ per combination. Its price is absolute rather than a
 * delta from the product's base price: deltas silently drift when the base price changes, and a
 * pricing bug on a live catalog is expensive.
 */
@Entity
@Table(name = "product_variants")
@Getter
@Setter
@NoArgsConstructor
public class ProductVariant extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "sku", nullable = false, unique = true, length = 64)
    private String sku;

    @Column(name = "barcode", length = 64)
    private String barcode;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "compare_at_price", precision = 12, scale = 2)
    private BigDecimal compareAtPrice;

    @Column(name = "cost_price", precision = 12, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "weight_grams")
    private Integer weightGrams;

    /** Swatch-specific image, shown when this variant is selected. */
    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "position", nullable = false)
    private int position = 0;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "variant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<VariantAttributeValue> attributeValues = new LinkedHashSet<>();

    public Money price() {
        return Money.of(price, product.getCurrency());
    }

    public boolean isOnSale() {
        return compareAtPrice != null && compareAtPrice.compareTo(price) > 0;
    }

    /**
     * Human-readable axis summary - "أحمر / كبير" - frozen onto order lines so the description
     * survives a later rename of the attribute value.
     */
    public String label(String locale) {
        return attributeValues.stream()
                .map(value -> value.getAttributeValue().value(locale))
                .collect(Collectors.joining(" / "));
    }

    /** Falls back to the parent product's weight when the variant does not override it. */
    public int effectiveWeightGrams() {
        if (weightGrams != null) {
            return weightGrams;
        }
        return product.getWeightGrams() == null ? 0 : product.getWeightGrams();
    }
}
