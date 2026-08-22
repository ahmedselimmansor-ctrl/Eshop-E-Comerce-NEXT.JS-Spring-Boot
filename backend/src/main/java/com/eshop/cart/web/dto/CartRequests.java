package com.eshop.cart.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Request bodies for the cart endpoints. */
public final class CartRequests {

    private CartRequests() {
    }

    /**
     * Note what is absent: there is no price field. The server looks the price up from the catalog,
     * because a client-supplied price is a client-supplied discount.
     */
    @Schema(name = "AddCartItemRequest")
    public record AddItem(
            @NotNull UUID productId,

            @Schema(description = "Required for a VARIABLE product, omitted for a SIMPLE one")
            UUID variantId,

            @Min(1) @Max(999) int quantity
    ) {
    }

    @Schema(name = "UpdateCartItemRequest")
    public record UpdateItem(
            @Schema(description = "Zero removes the line")
            @Min(0) @Max(999) int quantity
    ) {
    }

    @Schema(name = "ApplyCouponRequest")
    public record ApplyCoupon(@NotBlank @Size(max = 40) String code) {
    }

    @Schema(name = "MergeCartRequest")
    public record Merge(
            @Schema(description = "The guest session whose cart should be folded into the user's")
            @NotBlank @Size(max = 64) String sessionId
    ) {
    }
}
