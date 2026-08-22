package com.eshop.cart.domain;

import com.eshop.common.domain.AuditableEntity;
import com.eshop.common.money.Money;
import com.eshop.user.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A shopping cart, owned either by a signed-in {@link User} or by an anonymous session.
 *
 * <p>Guest carts are keyed by a client-generated {@code X-Session-Id}. Requiring an account before
 * adding to cart is one of the largest single sources of checkout abandonment, so the cart works
 * for anyone and {@code CartService.merge} folds the guest cart into the user's on sign-in.
 *
 * <p>The stored totals are a <b>cached projection</b>, recomputed by {@code CartService} on every
 * mutation so a cart badge does not have to re-derive them. They are never trusted at checkout:
 * {@code CheckoutService} re-prices every line from the catalog before creating an order. A cart is
 * a shopper's working set, not a price quote.
 */
@Entity
@Table(name = "carts")
@Getter
@Setter
@NoArgsConstructor
public class Cart extends AuditableEntity {

    public enum Status {
        /** In use. At most one per identity, enforced by a partial unique index. */
        ACTIVE,
        /** Folded into another cart at sign-in. */
        MERGED,
        /** Became an order. */
        CONVERTED,
        /** Expired without checkout; retained for abandoned-cart analytics and recovery emails. */
        ABANDONED
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Opaque client-supplied identifier for a guest cart. */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = Money.DEFAULT_CURRENCY;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "coupon_code", length = 40)
    private String couponCode;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "shipping_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal shippingTotal = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "item_count", nullable = false)
    private int itemCount = 0;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /*
     * Left out of the paged entity graphs - Hibernate cannot page in SQL while a collection is
     * join-fetched. @BatchSize loads a whole page's worth in one extra query instead of one per row.
     */
    @BatchSize(size = 50)
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();

    // ------------------------------------------------------------- behaviour --

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isGuest() {
        return user == null;
    }

    public UUID ownerId() {
        return user == null ? null : user.getId();
    }

    /**
     * Whether the caller may act on this cart: the signed-in owner, or the holder of the guest
     * session id. Checked on every cart operation - a cart id alone must not grant access.
     */
    public boolean isOwnedBy(UUID userId, String sessionId) {
        if (user != null && userId != null) {
            return user.getId().equals(userId);
        }
        return this.sessionId != null && this.sessionId.equals(sessionId);
    }

    public Optional<CartItem> findItem(UUID productId, UUID variantId) {
        return items.stream()
                .filter(item -> item.getProduct().getId().equals(productId)
                        && java.util.Objects.equals(
                                item.getVariant() == null ? null : item.getVariant().getId(), variantId))
                .findFirst();
    }

    public void addItem(CartItem item) {
        item.setCart(this);
        items.add(item);
    }

    public void removeItem(CartItem item) {
        items.remove(item);
        item.setCart(null);
    }

    public void clearItems() {
        items.forEach(item -> item.setCart(null));
        items.clear();
    }

    public Money subtotalMoney() {
        return Money.of(subtotal, currency);
    }

    public Money grandTotalMoney() {
        return Money.of(grandTotal, currency);
    }

    public void markConverted() {
        status = Status.CONVERTED;
    }

    public void markMerged() {
        status = Status.MERGED;
    }
}
