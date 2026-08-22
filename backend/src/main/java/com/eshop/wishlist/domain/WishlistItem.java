package com.eshop.wishlist.domain;

import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductVariant;
import com.eshop.common.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;
import java.util.UUID;

/**
 * One saved product, optionally pinned to a specific variant.
 *
 * <p>Unlike a cart line this keeps no price or display snapshot. A wishlist is precisely where the
 * customer <em>wants</em> to see today's price — a stale one would hide the sale they were waiting
 * for — so everything is read live from the catalog at render time.
 */
@Entity
@Table(name = "wishlist_items")
@Getter
@Setter
@NoArgsConstructor
public class WishlistItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wishlist_id", nullable = false)
    private Wishlist wishlist;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** {@code null} means "the product", not "any variant". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    public boolean matches(UUID productId, UUID variantId) {
        return product.getId().equals(productId)
                && Objects.equals(variant == null ? null : variant.getId(), variantId);
    }
}
