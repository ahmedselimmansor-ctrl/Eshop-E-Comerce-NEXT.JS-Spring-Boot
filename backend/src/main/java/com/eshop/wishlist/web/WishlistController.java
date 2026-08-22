package com.eshop.wishlist.web;

import com.eshop.cart.domain.Cart;
import com.eshop.cart.web.dto.CartResponse;
import com.eshop.common.web.RequestContext;
import com.eshop.inventory.service.InventoryService;
import com.eshop.security.UserPrincipal;
import com.eshop.wishlist.domain.Wishlist;
import com.eshop.wishlist.domain.WishlistItem;
import com.eshop.wishlist.service.WishlistService;
import com.eshop.wishlist.web.dto.WishlistRequests;
import com.eshop.wishlist.web.dto.WishlistResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wishlist endpoints.
 *
 * <p>Everything except the share link requires a signed-in caller: the list belongs to a person,
 * not to a browser. Responses are {@code no-store} for the same reason the cart's are - a shared
 * cache entry here would show one customer another's saved items.
 */
@RestController
@RequestMapping("/api/v1/wishlist")
@RequiredArgsConstructor
@Tag(name = "Wishlist")
public class WishlistController {

    private final WishlistService wishlists;
    private final InventoryService inventory;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The caller's wishlist",
            description = "Creates an empty list on first access. Prices and availability are read "
                    + "live, so a saved item reflects today's sale rather than the day it was saved.")
    public ResponseEntity<WishlistResponse> get(@AuthenticationPrincipal UserPrincipal caller) {
        return ok(wishlists.currentResponse(caller.id(), RequestContext.locale()));
    }

    @PostMapping("/items")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Save a product",
            description = "Idempotent: saving something already saved succeeds and changes nothing.")
    public ResponseEntity<WishlistResponse> add(@AuthenticationPrincipal UserPrincipal caller,
                                                @Valid @RequestBody WishlistRequests.Add request) {

        return ok(wishlists.addResponse(caller.id(), request.productId(), request.variantId(),
                RequestContext.locale()));
    }

    @DeleteMapping("/items/{itemId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Remove a saved item")
    public ResponseEntity<WishlistResponse> remove(@AuthenticationPrincipal UserPrincipal caller,
                                                   @PathVariable UUID itemId) {

        return ok(wishlists.removeResponse(caller.id(), itemId, RequestContext.locale()));
    }

    @DeleteMapping("/products/{productId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Unsave a product",
            description = "For the heart toggle on a listing, which knows the product but not the "
                    + "wishlist item id. Unsaving something that was not saved is a no-op.")
    public ResponseEntity<WishlistResponse> removeProduct(
            @AuthenticationPrincipal UserPrincipal caller,
            @PathVariable UUID productId,
            @RequestParam(required = false) UUID variantId) {

        return ok(wishlists.removeProductResponse(caller.id(), productId, variantId,
                RequestContext.locale()));
    }

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Empty the wishlist")
    public ResponseEntity<WishlistResponse> clear(@AuthenticationPrincipal UserPrincipal caller) {
        return ok(wishlists.clearResponse(caller.id(), RequestContext.locale()));
    }

    @PostMapping("/items/{itemId}/move-to-cart")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Move a saved item into the cart",
            description = "The item leaves the wishlist only if the cart accepted it - an "
                    + "out-of-stock product fails the call and stays saved.")
    public ResponseEntity<WishlistService.MoveToCartResponse> moveToCart(
            @AuthenticationPrincipal UserPrincipal caller,
            @PathVariable UUID itemId,
            @Valid @RequestBody(required = false) WishlistRequests.MoveToCart request) {

        int quantity = request == null ? 1 : request.quantityOrOne();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(wishlists.moveToCartResponse(caller.id(), itemId, quantity,
                        RequestContext.locale()));
    }

    @PutMapping("/share")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Publish or revoke the share link",
            description = "Publishing mints a new token each time, so revoking and republishing "
                    + "invalidates the link that was already sent.")
    public ResponseEntity<WishlistResponse> share(@AuthenticationPrincipal UserPrincipal caller,
                                                  @Valid @RequestBody WishlistRequests.Share request) {

        return ok(wishlists.setPublicResponse(caller.id(), request.isPublic(),
                RequestContext.locale()));
    }

    @GetMapping("/shared/{token}")
    @SecurityRequirements
    @Operation(summary = "Read a shared wishlist",
            description = "Anonymous. The token is the authorisation; the owner's identity and the "
                    + "token itself are not echoed back.")
    public ResponseEntity<WishlistResponse> shared(@PathVariable String token) {
        return ok(wishlists.sharedResponse(token, RequestContext.locale()));
    }

    // --------------------------------------------------------------- helpers --

    /** Wishlists are per-customer and change on every interaction, so never cached. */
    private static ResponseEntity<WishlistResponse> ok(WishlistResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

}
