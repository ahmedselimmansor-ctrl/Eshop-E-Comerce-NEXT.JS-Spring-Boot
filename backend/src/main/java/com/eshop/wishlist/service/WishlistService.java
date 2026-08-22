package com.eshop.wishlist.service;

import com.eshop.cart.domain.Cart;
import com.eshop.cart.service.CartService;
import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductVariant;
import com.eshop.catalog.service.ProductService;
import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.user.repository.UserRepository;
import com.eshop.wishlist.domain.Wishlist;
import com.eshop.wishlist.domain.WishlistItem;
import com.eshop.wishlist.repository.WishlistItemRepository;
import com.eshop.wishlist.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Saved items.
 *
 * <p>Deliberately simpler than the cart: no pricing, no stock hold, no guest support. A wishlist
 * outlives a session by design - it is the list a customer comes back to in a month - and anchoring
 * it to a browser cookie would quietly lose it. Guests are asked to sign in instead.
 *
 * <p>Every method takes the caller's id and resolves the list from it. A wishlist id is never
 * accepted from the client for a mutation, so there is no id to probe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistService {

    /** Generous, but bounded: an unbounded list is an unbounded query and an unbounded response. */
    private static final int MAX_ITEMS = 200;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WishlistRepository wishlists;
    private final WishlistItemRepository wishlistItems;
    private final ProductService products;
    private final CartService carts;
    private final UserRepository users;

    /**
     * The caller's default list, created on first access.
     *
     * <p>Read-only paths call this too, so it is not annotated {@code readOnly} - the first ever
     * read is also the insert.
     */
    @Transactional
    public Wishlist getOrCreate(UUID userId) {
        return initialised(wishlists.findByOwnerAndName(userId, Wishlist.DEFAULT_NAME)
                .orElseGet(() -> create(userId)));
    }

    /**
     * Touches everything the response renders, while the session is still open.
     *
     * <p>{@code open-in-view} is off, so the aggregate leaves this class detached. The read paths
     * come back through an entity graph that already loads all of this, but a write path does not:
     * a product fetched by {@code products.require} during {@link #add} arrives with its images and
     * brand as uninitialised proxies, and serialising the response would then fail with a
     * {@code LazyInitializationException} - after the row was already committed.
     */
    private Wishlist initialised(Wishlist wishlist) {
        for (WishlistItem item : wishlist.getItems()) {
            Product product = item.getProduct();
            product.getImages().size();
            if (product.getBrand() != null) {
                product.getBrand().getSlug();
            }
            if (item.getVariant() != null) {
                item.getVariant().getSku();
            }
        }
        return wishlist;
    }

    private Wishlist create(UUID userId) {
        var wishlist = new Wishlist();
        wishlist.setUser(users.findActiveById(userId)
                .orElseThrow(() -> NotFoundException.of("User", userId)));
        wishlist.setName(Wishlist.DEFAULT_NAME);

        try {
            return wishlists.saveAndFlush(wishlist);
        } catch (DataIntegrityViolationException race) {
            // Two tabs opened the wishlist at once. The unique (user_id, name) index decided which
            // insert wins; the loser reads the winner's row rather than failing the request.
            return wishlists.findByOwnerAndName(userId, Wishlist.DEFAULT_NAME)
                    .orElseThrow(() -> race);
        }
    }

    /**
     * Adds a product, or does nothing if it is already saved.
     *
     * <p>Idempotent on purpose. A heart button is tapped twice as often as it is tapped once, and a
     * 409 for "already in your wishlist" is a worse experience than silently agreeing.
     */
    @Transactional
    public Wishlist add(UUID userId, UUID productId, UUID variantId) {
        Wishlist wishlist = getOrCreate(userId);

        if (wishlist.find(productId, variantId).isPresent()) {
            return wishlist;
        }
        if (wishlist.getItems().size() >= MAX_ITEMS) {
            throw new BusinessException(ErrorCode.CART_LIMIT_EXCEEDED,
                    "Wishlist limit of " + MAX_ITEMS + " items reached");
        }

        Product product = products.require(productId);

        var item = new WishlistItem();
        item.setProduct(product);

        if (variantId != null) {
            ProductVariant variant = products.requireVariant(variantId);
            if (!variant.getProduct().getId().equals(productId)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Variant " + variantId + " does not belong to product " + productId);
            }
            item.setVariant(variant);
        }

        wishlist.add(item);
        return initialised(wishlists.save(wishlist));
    }

    /** Removes by item id. Unknown ids are a 404, not a silent success - the UI should resync. */
    @Transactional
    public Wishlist remove(UUID userId, UUID itemId) {
        Wishlist wishlist = getOrCreate(userId);
        if (!wishlist.remove(itemId)) {
            throw NotFoundException.of("Wishlist item", itemId);
        }
        return initialised(wishlists.save(wishlist));
    }

    /**
     * Removes by product, which is what a listing page's filled heart can offer.
     *
     * <p>Unlike {@link #remove}, a miss here is not an error: the customer's intent was "make this
     * not saved", and it already is.
     */
    @Transactional
    public Wishlist removeProduct(UUID userId, UUID productId, UUID variantId) {
        Wishlist wishlist = getOrCreate(userId);
        wishlist.find(productId, variantId)
                .ifPresent(item -> wishlist.getItems().remove(item));
        return initialised(wishlists.save(wishlist));
    }

    @Transactional
    public Wishlist clear(UUID userId) {
        Wishlist wishlist = getOrCreate(userId);
        wishlist.getItems().clear();
        return wishlists.save(wishlist);
    }

    /**
     * Moves one saved item into the cart.
     *
     * <p>The wishlist row is dropped only after the cart accepted the item. If the product went out
     * of stock or was delisted, {@code CartService} throws, this transaction rolls back, and the
     * customer still has their saved item - losing it in exchange for an error would be the worst
     * possible outcome.
     */
    @Transactional
    public MoveResult moveToCart(UUID userId, UUID itemId, int quantity) {
        Wishlist wishlist = getOrCreate(userId);

        WishlistItem item = wishlist.getItems().stream()
                .filter(candidate -> itemId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> NotFoundException.of("Wishlist item", itemId));

        Cart cart = carts.addItem(userId, null,
                item.getProduct().getId(),
                item.getVariant() == null ? null : item.getVariant().getId(),
                quantity);

        wishlist.getItems().remove(item);
        wishlists.save(wishlist);

        return new MoveResult(initialised(wishlist), cart);
    }

    // ----------------------------------------------------------------- sharing --

    /**
     * Turns sharing on or off.
     *
     * <p>Enabling mints a fresh token every time rather than reusing the old one. That makes
     * "unshare then reshare" actually revoke the previous link, which is the only reading of those
     * two actions a customer would expect.
     */
    @Transactional
    public Wishlist setPublic(UUID userId, boolean share) {
        Wishlist wishlist = getOrCreate(userId);
        wishlist.setPublic(share);
        wishlist.setShareToken(share ? newShareToken() : null);
        return initialised(wishlists.save(wishlist));
    }

    /** Anonymous read of a shared list. The token is the whole authorisation. */
    @Transactional(readOnly = true)
    public Wishlist shared(String token) {
        return initialised(wishlists.findShared(token)
                .orElseThrow(() -> NotFoundException.of("Shared wishlist", token)));
    }

    /** Which of these products the caller has already saved. Empty for signed-out callers. */
    @Transactional(readOnly = true)
    public Set<UUID> savedAmong(UUID userId, Collection<UUID> productIds) {
        if (userId == null || productIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(wishlists.savedProductIds(userId, productIds));
    }

    @Transactional(readOnly = true)
    public long count(UUID wishlistId) {
        return wishlistItems.countByWishlistId(wishlistId);
    }

    /** 128 bits, hex-encoded - unguessable, and short enough to survive being pasted into WhatsApp. */
    private static String newShareToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The wishlist and the cart after a move, so the caller can refresh both badges at once. */
    public record MoveResult(Wishlist wishlist, Cart cart) {
    }

    /** Convenience for callers that only need the saved product ids. */
    public List<UUID> productIds(Wishlist wishlist) {
        return wishlist.getItems().stream().map(item -> item.getProduct().getId()).toList();
    }
}
