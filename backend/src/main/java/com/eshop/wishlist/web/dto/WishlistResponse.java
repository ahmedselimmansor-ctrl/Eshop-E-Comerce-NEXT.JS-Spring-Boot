package com.eshop.wishlist.web.dto;

import com.eshop.catalog.web.dto.ProductCardResponse;
import com.eshop.wishlist.domain.Wishlist;
import com.eshop.wishlist.domain.WishlistItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A wishlist as the storefront renders it.
 *
 * <p>Each entry carries a full product card because that is exactly what the page draws, and
 * live availability because "add to cart" has to be disabled for items that sold out while they
 * were saved — which, for a wishlist, is the common case rather than the exception.
 */
@Schema(name = "WishlistResponse")
public record WishlistResponse(
        UUID id,
        String name,

        @Schema(description = "Whether the list is readable via its share link")
        boolean isPublic,

        @Schema(description = "Present only for the owner of a shared list; null otherwise")
        String shareToken,

        int itemCount,
        List<Item> items
) {

    @Schema(name = "WishlistItemResponse")
    public record Item(
            UUID id,

            @Schema(description = "Saved variant, or null when the whole product was saved")
            UUID variantId,

            String variantLabel,

            @Schema(description = "Units currently available for this exact SKU")
            int availableQuantity,

            boolean inStock,
            Instant savedAt,
            ProductCardResponse product
    ) {
    }

    /**
     * @param availability availability keyed by wishlist item id, as resolved in one bulk lookup
     * @param includeToken false when rendering someone else's shared list — the token is the
     *                     capability, and echoing it to a viewer would let them reshare it
     */
    public static WishlistResponse from(Wishlist wishlist,
                                        String locale,
                                        Map<UUID, Integer> availability,
                                        boolean includeToken) {

        List<Item> items = wishlist.getItems().stream()
                .map(item -> toItem(item, locale, availability.getOrDefault(item.getId(), 0)))
                .toList();

        return new WishlistResponse(
                wishlist.getId(),
                wishlist.getName(),
                wishlist.isPublic(),
                includeToken ? wishlist.getShareToken() : null,
                items.size(),
                items);
    }

    private static Item toItem(WishlistItem item, String locale, int available) {
        var variant = item.getVariant();

        return new Item(
                item.getId(),
                variant == null ? null : variant.getId(),
                variant == null ? null : variant.label(locale),
                available,
                available > 0,
                item.getCreatedAt(),
                ProductCardResponse.from(item.getProduct(), locale, available > 0));
    }
}
