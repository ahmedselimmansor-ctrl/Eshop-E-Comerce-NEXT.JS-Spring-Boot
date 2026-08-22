package com.eshop.cart.domain;

import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductVariant;
import com.eshop.common.domain.AuditableEntity;
import com.eshop.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line in a cart.
 *
 * <p>{@code unitPrice} and {@code snapshot} record the product as it was when added. The snapshot
 * keeps the cart renderable in one query - no join to the catalog to show a name and a thumbnail -
 * and the captured price is what {@code CartService.validate} compares against the live catalog to
 * raise {@code PRICE_CHANGED} before checkout. The customer is shown the change and asked to
 * confirm; the order is always created from live prices.
 */
@Entity
@Table(name = "cart_items")
@Getter
@Setter
@NoArgsConstructor
public class CartItem extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** {@code null} for a simple product. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** Display fields captured at add-to-cart time: name, image, SKU, slug, variant label. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> snapshot = new LinkedHashMap<>();

    public Money unitPriceMoney(String currency) {
        return Money.of(unitPrice, currency);
    }

    public Money lineTotal(String currency) {
        return Money.of(unitPrice, currency).times(quantity);
    }

    /** The price this line would cost today, straight from the catalog. */
    public BigDecimal currentCatalogPrice() {
        return variant != null ? variant.getPrice() : product.getBasePrice();
    }

    public boolean priceChanged() {
        return currentCatalogPrice().compareTo(unitPrice) != 0;
    }

    public void refreshPrice() {
        unitPrice = currentCatalogPrice();
    }

    public String snapshotString(String key) {
        Object value = snapshot.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** Rebuilds the display snapshot from the current catalog state. */
    public void captureSnapshot(String locale) {
        Map<String, Object> captured = new LinkedHashMap<>();
        captured.put("nameAr", product.getNameAr());
        captured.put("nameEn", product.getNameEn());
        captured.put("slug", product.getSlug());
        captured.put("sku", variant != null ? variant.getSku() : product.getSku());
        captured.put("imageUrl", variant != null && variant.getImageUrl() != null
                ? variant.getImageUrl()
                : product.primaryImage().map(image -> image.getUrl()).orElse(null));

        if (variant != null) {
            captured.put("variantLabelAr", variant.label("ar"));
            captured.put("variantLabelEn", variant.label("en"));
        }
        captured.values().removeIf(java.util.Objects::isNull);
        snapshot = captured;
    }
}
