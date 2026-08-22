package com.eshop.order.domain;

import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductVariant;
import com.eshop.common.domain.BaseEntity;
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

import java.math.BigDecimal;

/**
 * One line of a placed order, with the product description frozen at purchase time.
 *
 * <p>The {@code product} and {@code variant} references exist so "buy it again" and sales reporting
 * can find the catalog entry, and they are {@code ON DELETE SET NULL} — a deleted product must not
 * take order history with it. Nothing displayed on an invoice is read through those references.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Nullable: the product may be deleted long after the order ships. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "name_ar", nullable = false)
    private String nameAr;

    @Column(name = "name_en")
    private String nameEn;

    /** "أحمر / كبير" — the variant axes as they read at purchase time. */
    @Column(name = "variant_label_ar")
    private String variantLabelAr;

    @Column(name = "variant_label_en")
    private String variantLabelEn;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** This line's share of any order-level discount, apportioned by value. */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** {@code unitPrice × quantity − discount + tax}, stored so an invoice never re-derives it. */
    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "fulfilled_quantity", nullable = false)
    private int fulfilledQuantity = 0;

    @Column(name = "refunded_quantity", nullable = false)
    private int refundedQuantity = 0;

    public String name(String locale) {
        return "en".equalsIgnoreCase(locale) && nameEn != null && !nameEn.isBlank() ? nameEn : nameAr;
    }

    public String variantLabel(String locale) {
        return "en".equalsIgnoreCase(locale) && variantLabelEn != null ? variantLabelEn : variantLabelAr;
    }

    public Money unitPriceMoney(String currency) {
        return Money.of(unitPrice, currency);
    }

    public Money lineTotalMoney(String currency) {
        return Money.of(lineTotal, currency);
    }

    public int remainingToFulfil() {
        return quantity - fulfilledQuantity;
    }

    public int remainingToRefund() {
        return quantity - refundedQuantity;
    }

    /** Recomputes the stored total after prices, discounts, or tax are apportioned. */
    public void recomputeLineTotal() {
        lineTotal = unitPrice
                .multiply(BigDecimal.valueOf(quantity))
                .subtract(discountAmount)
                .add(taxAmount)
                .max(BigDecimal.ZERO);
    }
}
