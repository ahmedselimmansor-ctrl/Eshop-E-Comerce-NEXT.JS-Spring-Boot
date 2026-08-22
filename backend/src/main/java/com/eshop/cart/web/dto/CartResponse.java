package com.eshop.cart.web.dto;

import com.eshop.cart.domain.Cart;
import com.eshop.cart.domain.CartItem;
import com.eshop.cart.service.CartService;
import com.eshop.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The cart as the storefront renders it.
 *
 * <p>Line display fields come from the stored snapshot, so the cart renders without joining the
 * catalog. Availability and price-change flags come from live data, because those are exactly the
 * things a snapshot must not be trusted for.
 */
@Schema(name = "CartResponse")
public record CartResponse(
        UUID id,
        String currency,
        int itemCount,
        List<Item> items,
        AppliedCoupon coupon,
        Totals totals,

        @Schema(description = "Problems found by the last validation; empty when the cart is ready")
        List<IssueResponse> issues
) {

    public record Item(
            UUID id,
            UUID productId,
            UUID variantId,
            String slug,
            String name,
            String variantLabel,
            String sku,
            String imageUrl,
            Money unitPrice,
            int quantity,
            Money lineTotal,

            @Schema(description = "Sellable units right now - the cap for a quantity stepper")
            int availableQuantity,

            boolean inStock,

            @Schema(description = "True when the catalog price differs from the price captured "
                    + "when this item was added")
            boolean priceChanged,

            Money currentPrice
    ) {
    }

    public record AppliedCoupon(String code, String type, Money discount, boolean freeShipping) {
    }

    public record Totals(Money subtotal, Money discount, Money tax, Money shipping, Money grandTotal) {
    }

    public record IssueResponse(UUID itemId, String type, int available, Money currentPrice) {
    }

    /**
     * @param availability sellable units keyed by cart item id, resolved by the caller in one bulk
     *                     lookup rather than per line
     */
    public static CartResponse from(Cart cart,
                                    String locale,
                                    Map<UUID, Integer> availability,
                                    List<CartService.Issue> issues) {
        boolean english = "en".equalsIgnoreCase(locale);

        List<Item> items = cart.getItems().stream().map(item -> {
            int available = availability.getOrDefault(item.getId(), 0);
            return new Item(
                    item.getId(),
                    item.getProduct().getId(),
                    item.getVariant() == null ? null : item.getVariant().getId(),
                    item.snapshotString("slug"),
                    item.snapshotString(english ? "nameEn" : "nameAr") != null
                            ? item.snapshotString(english ? "nameEn" : "nameAr")
                            : item.snapshotString("nameAr"),
                    item.snapshotString(english ? "variantLabelEn" : "variantLabelAr"),
                    item.snapshotString("sku"),
                    item.snapshotString("imageUrl"),
                    item.unitPriceMoney(cart.getCurrency()),
                    item.getQuantity(),
                    item.lineTotal(cart.getCurrency()),
                    available,
                    available > 0,
                    item.priceChanged(),
                    Money.of(item.currentCatalogPrice(), cart.getCurrency()));
        }).toList();

        AppliedCoupon coupon = cart.getCouponCode() == null
                ? null
                : new AppliedCoupon(cart.getCouponCode(), null,
                        Money.of(cart.getDiscountTotal(), cart.getCurrency()),
                        cart.getDiscountTotal().signum() == 0);

        Totals totals = new Totals(
                Money.of(cart.getSubtotal(), cart.getCurrency()),
                Money.of(cart.getDiscountTotal(), cart.getCurrency()),
                Money.of(cart.getTaxTotal(), cart.getCurrency()),
                Money.of(cart.getShippingTotal(), cart.getCurrency()),
                Money.of(cart.getGrandTotal(), cart.getCurrency()));

        List<IssueResponse> issueResponses = issues.stream()
                .map(issue -> new IssueResponse(
                        issue.itemId(), issue.type().name(), issue.available(), issue.currentPrice()))
                .toList();

        return new CartResponse(cart.getId(), cart.getCurrency(), cart.getItemCount(),
                items, coupon, totals, issueResponses);
    }

    /** Keys availability by cart item id, which is what the response needs. */
    public static Map<UUID, Integer> availabilityByItem(Cart cart, Map<?, Integer> bySku) {
        return cart.getItems().stream().collect(java.util.stream.Collectors.toMap(
                CartItem::getId,
                item -> bySku.getOrDefault(
                        new com.eshop.inventory.service.InventoryService.SkuRef(
                                item.getProduct().getId(),
                                item.getVariant() == null ? null : item.getVariant().getId()),
                        0)));
    }
}
