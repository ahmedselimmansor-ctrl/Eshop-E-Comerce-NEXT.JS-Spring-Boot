package com.eshop.wishlist.domain;

import com.eshop.common.domain.AuditableEntity;
import com.eshop.user.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A saved-items list belonging to one customer.
 *
 * <p>The schema allows several named lists per user; the storefront only ever asks for
 * {@link #DEFAULT_NAME}, which {@code WishlistService} creates on first access. Modelling the extra
 * lists now costs nothing and means "move to my Gifts list" never needs a migration.
 *
 * <p>Sharing is opt-in and capability-based: making a list public mints a {@code shareToken}, and
 * that token is the only way to read it anonymously. Turning sharing off clears the token, so a link
 * that was already sent stops working — which is what "unshare" has to mean.
 */
@Entity
@Table(name = "wishlists")
@Getter
@Setter
@NoArgsConstructor
public class Wishlist extends AuditableEntity {

    public static final String DEFAULT_NAME = "default";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "name", nullable = false, length = 60)
    private String name = DEFAULT_NAME;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    /** {@code null} unless the list is shared. Unique across all wishlists. */
    @Column(name = "share_token", length = 32)
    private String shareToken;

    /**
     * Newest first — a wishlist is read far more often than written, and the item just saved is the
     * one the customer expects at the top.
     */
    @OneToMany(mappedBy = "wishlist", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("createdAt DESC")
    private List<WishlistItem> items = new ArrayList<>();

    public Optional<WishlistItem> find(UUID productId, UUID variantId) {
        return items.stream().filter(item -> item.matches(productId, variantId)).findFirst();
    }

    public void add(WishlistItem item) {
        item.setWishlist(this);
        items.add(item);
    }

    public boolean remove(UUID itemId) {
        return items.removeIf(item -> itemId.equals(item.getId()));
    }

    public boolean ownedBy(UUID userId) {
        return user != null && user.getId().equals(userId);
    }
}
