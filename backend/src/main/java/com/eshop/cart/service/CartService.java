package com.eshop.cart.service;

import com.eshop.cart.domain.Cart;
import com.eshop.cart.web.dto.CartResponse;
import com.eshop.cart.domain.CartItem;
import com.eshop.cart.repository.CartItemRepository;
import com.eshop.cart.repository.CartRepository;
import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductVariant;
import com.eshop.catalog.repository.ProductVariantRepository;
import com.eshop.catalog.service.CategoryService;
import com.eshop.catalog.service.ProductService;
import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.common.money.Money;
import com.eshop.config.AppProperties;
import com.eshop.inventory.service.InventoryService;
import com.eshop.promotion.service.CouponService;
import com.eshop.promotion.service.PriceableLine;
import com.eshop.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The shopping cart.
 *
 * <p>Two rules hold everywhere in this class:
 * <ol>
 *   <li><b>Prices come from the catalog, never from the client.</b> A request says what to add and
 *       how many; the price is looked up. Accepting a submitted price is the single most commonly
 *       exploited flaw in a storefront API.</li>
 *   <li><b>Ownership is checked on every operation.</b> A cart id is not a capability - the caller
 *       must be the signed-in owner or hold the guest session id.</li>
 * </ol>
 *
 * <p>Stock is checked here for a good error message, but nothing is <em>held</em>. Reserving on
 * add-to-cart would let anyone drain a flash sale by filling a cart they never intend to pay for.
 * The hold is taken at checkout, for thirty minutes, against a real order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final ProductService products;
    private final ProductVariantRepository variants;
    private final CategoryService categories;
    private final InventoryService inventory;
    private final CouponService coupons;
    private final UserRepository users;
    private final AppProperties properties;

    // ------------------------------------------------------------ retrieval --

    /** Loads the caller's active cart, creating an empty one on first use. */
    @Transactional
    public Cart getOrCreate(UUID userId, String sessionId) {
        Optional<Cart> existing = userId != null
                ? carts.findActiveByUser(userId)
                : carts.findActiveBySession(requireSession(sessionId));

        return existing.orElseGet(() -> create(userId, sessionId));
    }

    @Transactional(readOnly = true)
    public Optional<Cart> find(UUID userId, String sessionId) {
        return userId != null
                ? carts.findActiveByUser(userId)
                : (sessionId == null ? Optional.empty() : carts.findActiveBySession(sessionId));
    }

    private Cart create(UUID userId, String sessionId) {
        Cart cart = new Cart();
        if (userId != null) {
            cart.setUser(users.findActiveById(userId)
                    .orElseThrow(() -> NotFoundException.of("User", userId)));
            cart.setExpiresAt(Instant.now().plusSeconds(
                    properties.getCart().getUserTtlDays() * 86_400L));
        } else {
            cart.setSessionId(requireSession(sessionId));
            cart.setExpiresAt(Instant.now().plusSeconds(
                    properties.getCart().getGuestTtlDays() * 86_400L));
        }
        cart.setCurrency(properties.getDefaultCurrency());
        return carts.save(cart);
    }

    // -------------------------------------------------------------- mutation --

    @Transactional
    public Cart addItem(UUID userId, String sessionId, UUID productId, UUID variantId, int quantity) {
        Cart cart = getOrCreate(userId, sessionId);

        Product product = products.require(productId);
        if (!product.isPurchasable()) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE,
                    "Product " + productId + " is not purchasable");
        }

        ProductVariant variant = resolveVariant(product, variantId);

        if (cart.getItems().size() >= properties.getCart().getMaxItems()
                && cart.findItem(productId, variantId).isEmpty()) {
            throw new BusinessException(ErrorCode.CART_LIMIT_EXCEEDED, "Cart item limit reached");
        }

        Optional<CartItem> existing = cart.findItem(productId, variantId);
        int newQuantity = existing.map(CartItem::getQuantity).orElse(0) + quantity;
        newQuantity = Math.min(newQuantity, properties.getCart().getMaxQuantityPerItem());

        assertAvailable(productId, variantId, newQuantity);

        CartItem item = existing.orElseGet(() -> {
            CartItem created = new CartItem();
            created.setProduct(product);
            created.setVariant(variant);
            cart.addItem(created);
            return created;
        });

        item.setQuantity(newQuantity);
        // Refreshed on every touch so a price cut is reflected immediately; an increase surfaces as
        // PRICE_CHANGED at validate() rather than silently repricing what the shopper already saw.
        item.setUnitPrice(variant != null ? variant.getPrice() : product.getBasePrice());
        item.captureSnapshot(properties.getDefaultLocale());

        return recalculate(cart);
    }

    @Transactional
    public Cart updateQuantity(UUID userId, String sessionId, UUID itemId, int quantity) {
        Cart cart = requireOwnedCart(userId, sessionId);

        CartItem item = cart.getItems().stream()
                .filter(candidate -> candidate.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> NotFoundException.of("CartItem", itemId));

        if (quantity <= 0) {
            cart.removeItem(item);
            cartItems.delete(item);
            return recalculate(cart);
        }

        int capped = Math.min(quantity, properties.getCart().getMaxQuantityPerItem());
        assertAvailable(item.getProduct().getId(),
                item.getVariant() == null ? null : item.getVariant().getId(), capped);

        item.setQuantity(capped);
        return recalculate(cart);
    }

    @Transactional
    public Cart removeItem(UUID userId, String sessionId, UUID itemId) {
        Cart cart = requireOwnedCart(userId, sessionId);
        cart.getItems().removeIf(item -> item.getId().equals(itemId));
        cartItems.deleteFromCart(itemId, cart.getId());
        return recalculate(cart);
    }

    @Transactional
    public Cart clear(UUID userId, String sessionId) {
        Cart cart = requireOwnedCart(userId, sessionId);
        cart.clearItems();
        cartItems.deleteAllInCart(cart.getId());
        cart.setCouponCode(null);
        return recalculate(cart);
    }

    // ---------------------------------------------------------------- coupon --

    /**
     * Applies a code to the cart for display.
     *
     * <p>Advisory only - the same code is revalidated under a lock at checkout. Storing it here is
     * a convenience so the shopper does not have to retype it, not a reservation of the discount.
     */
    @Transactional
    public CouponService.Evaluation applyCoupon(UUID userId, String sessionId, String code) {
        Cart cart = requireOwnedCart(userId, sessionId);

        if (cart.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY, "Cannot apply a coupon to an empty cart");
        }

        CouponService.Evaluation evaluation = coupons.evaluate(code, priceableLines(cart), userId);
        if (!evaluation.valid()) {
            throw new BusinessException(evaluation.reason(), "Coupon rejected: " + evaluation.reason());
        }

        cart.setCouponCode(evaluation.code());
        recalculate(cart);
        return evaluation;
    }

    @Transactional
    public Cart removeCoupon(UUID userId, String sessionId) {
        Cart cart = requireOwnedCart(userId, sessionId);
        cart.setCouponCode(null);
        return recalculate(cart);
    }

    // ----------------------------------------------------------------- merge --

    /**
     * Folds a guest cart into the signed-in customer's cart at login.
     *
     * <p>Quantities are summed rather than replaced: a shopper who added a laptop on their phone
     * and another on their laptop meant to buy two. The guest cart is marked {@code MERGED} rather
     * than deleted, so the merge is auditable if a customer reports a missing item.
     */
    @Transactional
    public Cart merge(UUID userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return getOrCreate(userId, null);
        }

        Optional<Cart> guestCart = carts.findActiveBySession(sessionId);
        Cart userCart = getOrCreate(userId, null);

        if (guestCart.isEmpty() || guestCart.get().getId().equals(userCart.getId())) {
            return userCart;
        }

        Cart guest = guestCart.get();
        for (CartItem guestItem : List.copyOf(guest.getItems())) {
            UUID productId = guestItem.getProduct().getId();
            UUID variantId = guestItem.getVariant() == null ? null : guestItem.getVariant().getId();

            Optional<CartItem> existing = userCart.findItem(productId, variantId);
            int merged = existing.map(CartItem::getQuantity).orElse(0) + guestItem.getQuantity();
            merged = Math.min(merged, properties.getCart().getMaxQuantityPerItem());

            // A product that went out of stock or was archived while the guest browsed is skipped
            // rather than failing the whole merge - and the sign-in it is attached to.
            int available = inventory.available(productId, variantId);
            if (available <= 0) {
                log.info("cart_merge_skipped_unavailable product={} variant={}", productId, variantId);
                continue;
            }
            merged = Math.min(merged, available);

            if (existing.isPresent()) {
                existing.get().setQuantity(merged);
            } else {
                CartItem moved = new CartItem();
                moved.setProduct(guestItem.getProduct());
                moved.setVariant(guestItem.getVariant());
                moved.setQuantity(merged);
                moved.setUnitPrice(guestItem.getUnitPrice());
                moved.setSnapshot(guestItem.getSnapshot());
                userCart.addItem(moved);
            }
        }

        if (userCart.getCouponCode() == null && guest.getCouponCode() != null) {
            userCart.setCouponCode(guest.getCouponCode());
        }

        guest.clearItems();
        guest.markMerged();
        carts.save(guest);

        log.info("cart_merged user={} from_session={} items={}", userId, sessionId, userCart.getItems().size());
        return recalculate(userCart);
    }

    // ------------------------------------------------------------- validation --

    /**
     * Pre-checkout check: has anything changed since these items were added?
     *
     * <p>Returns issues instead of throwing, because the customer needs to see all of them at once
     * - fixing them one 409 at a time is a miserable checkout.
     */
    @Transactional
    public List<Issue> validate(UUID userId, String sessionId) {
        Cart cart = requireOwnedCart(userId, sessionId);
        List<Issue> issues = new ArrayList<>();

        for (CartItem item : cart.getItems()) {
            UUID productId = item.getProduct().getId();
            UUID variantId = item.getVariant() == null ? null : item.getVariant().getId();

            if (!item.getProduct().isPurchasable()) {
                issues.add(new Issue(item.getId(), IssueType.UNAVAILABLE, 0, null));
                continue;
            }
            if (item.getVariant() != null && !item.getVariant().isActive()) {
                issues.add(new Issue(item.getId(), IssueType.UNAVAILABLE, 0, null));
                continue;
            }

            int available = inventory.available(productId, variantId);
            if (available <= 0) {
                issues.add(new Issue(item.getId(), IssueType.OUT_OF_STOCK, 0, null));
            } else if (available < item.getQuantity()) {
                issues.add(new Issue(item.getId(), IssueType.QUANTITY_REDUCED, available, null));
            }

            if (item.priceChanged()) {
                issues.add(new Issue(item.getId(), IssueType.PRICE_CHANGED, item.getQuantity(),
                        Money.of(item.currentCatalogPrice(), cart.getCurrency())));
            }
        }
        return issues;
    }

    /** Applies the fixes for the issues above: clamp quantities, drop dead lines, accept new prices. */
    @Transactional
    public Cart reconcile(UUID userId, String sessionId) {
        Cart cart = requireOwnedCart(userId, sessionId);

        cart.getItems().removeIf(item -> {
            boolean dead = !item.getProduct().isPurchasable()
                    || (item.getVariant() != null && !item.getVariant().isActive());
            if (dead) {
                cartItems.delete(item);
            }
            return dead;
        });

        for (CartItem item : List.copyOf(cart.getItems())) {
            UUID variantId = item.getVariant() == null ? null : item.getVariant().getId();
            int available = inventory.available(item.getProduct().getId(), variantId);

            if (available <= 0) {
                cart.removeItem(item);
                cartItems.delete(item);
            } else {
                if (available < item.getQuantity()) {
                    item.setQuantity(available);
                }
                item.refreshPrice();
            }
        }
        return recalculate(cart);
    }

    // ------------------------------------------------------------ arithmetic --

    /**
     * Recomputes the cached totals.
     *
     * <p>Shipping is not included: it depends on a destination address that the cart does not have.
     * The checkout quote adds it once a governorate and a method are chosen.
     */
    @Transactional
    public Cart recalculate(Cart cart) {
        String currency = cart.getCurrency();

        Money subtotal = cart.getItems().stream()
                .map(item -> item.lineTotal(currency))
                .reduce(Money.zero(currency), Money::plus);

        Money discount = Money.zero(currency);
        if (cart.getCouponCode() != null && !cart.getItems().isEmpty()) {
            CouponService.Evaluation evaluation =
                    coupons.evaluate(cart.getCouponCode(), priceableLines(cart), cart.ownerId());
            if (evaluation.valid()) {
                discount = evaluation.discount();
            } else {
                // The code stopped being valid while it sat in the cart; drop it rather than
                // showing a discount that checkout will refuse.
                cart.setCouponCode(null);
            }
        }

        Money taxable = subtotal.minus(discount).atLeastZero();
        Money tax = taxable.percentage(properties.getCheckout().getDefaultTaxRate());

        cart.setSubtotal(subtotal.amount());
        cart.setDiscountTotal(discount.amount());
        cart.setTaxTotal(tax.amount());
        cart.setShippingTotal(BigDecimal.ZERO);
        cart.setGrandTotal(taxable.plus(tax).amount());
        cart.setItemCount(cart.getItems().stream().mapToInt(CartItem::getQuantity).sum());

        return carts.save(cart);
    }

    /**
     * Converts cart lines into the shape the discount engine consumes, resolving each product's
     * category ancestors so category-scoped coupons match sub-categories too. Ancestors are
     * memoised per call - a cart usually spans only a handful of distinct categories.
     */
    public List<PriceableLine> priceableLines(Cart cart) {
        Map<UUID, Set<UUID>> ancestorCache = new HashMap<>();

        return cart.getItems().stream().map(item -> {
            Product product = item.getProduct();
            UUID categoryId = product.getPrimaryCategory() == null
                    ? null : product.getPrimaryCategory().getId();

            Set<UUID> categoryIds = categoryId == null
                    ? Set.of()
                    : ancestorCache.computeIfAbsent(categoryId, categories::selfAndAncestorIds);

            return new PriceableLine(
                    product.getId(),
                    item.getVariant() == null ? null : item.getVariant().getId(),
                    categoryIds,
                    product.getBrand() == null ? null : product.getBrand().getId(),
                    item.unitPriceMoney(cart.getCurrency()),
                    item.getQuantity());
        }).toList();
    }

    // --------------------------------------------------------------- helpers --

    private Cart requireOwnedCart(UUID userId, String sessionId) {
        Cart cart = find(userId, sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_EMPTY, "No active cart"));

        if (!cart.isOwnedBy(userId, sessionId)) {
            // Reported as "no cart" rather than "forbidden" so a probe cannot confirm a cart exists.
            throw new BusinessException(ErrorCode.CART_EMPTY, "Cart does not belong to the caller");
        }
        return cart;
    }

    private ProductVariant resolveVariant(Product product, UUID variantId) {
        if (variantId == null) {
            if (product.getType() == com.eshop.catalog.domain.ProductType.VARIABLE) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Variant required")
                        .withViolation("variantId", "REQUIRED",
                                "برجاء اختيار أحد خيارات المنتج", null);
            }
            return null;
        }
        // Checks the pairing, not just the id - a variant id from a different product is rejected.
        return variants.findByIdAndProductId(variantId, product.getId())
                .filter(ProductVariant::isActive)
                .orElseThrow(() -> NotFoundException.of("ProductVariant", variantId));
    }

    private void assertAvailable(UUID productId, UUID variantId, int wanted) {
        int available = inventory.available(productId, variantId);
        if (available < wanted) {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK,
                    "Requested " + wanted + " but only " + available + " available")
                    .withProperty("availableQuantity", available)
                    .withViolation("quantity", "MAX_AVAILABLE",
                            available == 0 ? "المنتج غير متوفر حالياً" : "المتاح " + available + " فقط",
                            wanted);
        }
    }

    private static String requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Guest cart requires X-Session-Id")
                    .withViolation("X-Session-Id", "REQUIRED",
                            "معرّف الجلسة مطلوب لسلة الزائر", null);
        }
        return sessionId;
    }

    public enum IssueType {
        /** Product archived, deleted, or the chosen variant deactivated. */
        UNAVAILABLE,
        OUT_OF_STOCK,
        /** Fewer units remain than the cart holds; {@code available} carries the new maximum. */
        QUANTITY_REDUCED,
        /** The catalog price moved; {@code currentPrice} carries the new figure. */
        PRICE_CHANGED
    }

    public record Issue(UUID itemId, IssueType type, int available, Money currentPrice) {
    }

    // -------------------------------------------------------------- responses --

    /**
     * The cart as the API returns it.
     *
     * <p>Every mutation below returns this rather than the entity. {@code CartResponse} walks the
     * items and each item's product and variant, and it needs a per-line availability lookup on top,
     * so building it in a controller means reading four lazy associations off a detached cart. The
     * availability query is one bulk call for the whole cart, not one per line.
     */
    @Transactional(readOnly = true)
    public CartResponse toResponse(Cart cart, List<Issue> issues, String locale) {
        List<InventoryService.SkuRef> refs = cart.getItems().stream()
                .map(item -> new InventoryService.SkuRef(
                        item.getProduct().getId(),
                        item.getVariant() == null ? null : item.getVariant().getId()))
                .toList();

        Map<InventoryService.SkuRef, Integer> bySku = inventory.availabilityFor(refs);
        Map<UUID, Integer> byItem = CartResponse.availabilityByItem(cart, bySku);

        return CartResponse.from(cart, locale, byItem, issues);
    }

    @Transactional
    public CartResponse currentResponse(UUID userId, String sessionId, String locale) {
        return toResponse(getOrCreate(userId, sessionId), List.of(), locale);
    }

    @Transactional
    public CartResponse addItemResponse(UUID userId, String sessionId, UUID productId,
                                        UUID variantId, int quantity, String locale) {
        return toResponse(addItem(userId, sessionId, productId, variantId, quantity),
                List.of(), locale);
    }

    @Transactional
    public CartResponse updateQuantityResponse(UUID userId, String sessionId, UUID itemId,
                                               int quantity, String locale) {
        return toResponse(updateQuantity(userId, sessionId, itemId, quantity), List.of(), locale);
    }

    @Transactional
    public CartResponse removeItemResponse(UUID userId, String sessionId, UUID itemId,
                                           String locale) {
        return toResponse(removeItem(userId, sessionId, itemId), List.of(), locale);
    }

    @Transactional
    public CartResponse clearResponse(UUID userId, String sessionId, String locale) {
        return toResponse(clear(userId, sessionId), List.of(), locale);
    }

    @Transactional
    public CartResponse applyCouponResponse(UUID userId, String sessionId, String code,
                                            String locale) {
        applyCoupon(userId, sessionId, code);
        return toResponse(getOrCreate(userId, sessionId), List.of(), locale);
    }

    @Transactional
    public CartResponse removeCouponResponse(UUID userId, String sessionId, String locale) {
        return toResponse(removeCoupon(userId, sessionId), List.of(), locale);
    }

    @Transactional
    public CartResponse mergeResponse(UUID userId, String sessionId, String locale) {
        return toResponse(merge(userId, sessionId), List.of(), locale);
    }

    /** Validation issues alongside the cart they describe, so a client can act on both at once. */
    @Transactional
    public CartResponse validateResponse(UUID userId, String sessionId, String locale) {
        List<Issue> issues = validate(userId, sessionId);
        return toResponse(getOrCreate(userId, sessionId), issues, locale);
    }

    @Transactional
    public CartResponse reconcileResponse(UUID userId, String sessionId, String locale) {
        return toResponse(reconcile(userId, sessionId), List.of(), locale);
    }

}
