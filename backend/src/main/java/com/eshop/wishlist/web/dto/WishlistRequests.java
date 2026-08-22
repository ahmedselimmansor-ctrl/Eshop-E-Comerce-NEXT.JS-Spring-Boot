package com.eshop.wishlist.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Request bodies for the wishlist endpoints. */
public final class WishlistRequests {

    private WishlistRequests() {
    }

    @Schema(name = "WishlistAddRequest")
    public record Add(
            @NotNull UUID productId,

            @Schema(description = "Pin a specific variant; omit to save the product itself")
            UUID variantId
    ) {
    }

    @Schema(name = "WishlistMoveToCartRequest")
    public record MoveToCart(
            @Min(1) @Max(999)
            @Schema(defaultValue = "1")
            Integer quantity
    ) {
        public int quantityOrOne() {
            return quantity == null ? 1 : quantity;
        }
    }

    @Schema(name = "WishlistShareRequest")
    public record Share(
            @NotNull
            @Schema(description = "true mints a fresh share link; false revokes the existing one")
            Boolean isPublic
    ) {
    }
}
