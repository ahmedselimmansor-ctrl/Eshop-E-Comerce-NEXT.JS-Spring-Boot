package com.eshop.cart.web;

import com.eshop.cart.domain.Cart;
import com.eshop.cart.service.CartService;
import com.eshop.cart.web.dto.CartRequests;
import com.eshop.cart.web.dto.CartResponse;
import com.eshop.common.web.RequestContext;
import com.eshop.inventory.service.InventoryService;
import com.eshop.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cart endpoints, usable signed-in or as a guest.
 *
 * <p>A guest identifies their cart with {@code X-Session-Id}. Every response is {@code no-store}:
 * a cart is per-person and per-moment, and a cached copy at the CDN would be both a privacy leak
 * and a source of phantom items.
 */
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
@Tag(name = "Cart")
@SecurityRequirements
public class CartController {

    private final CartService carts;
    private final InventoryService inventory;

    @GetMapping
    @Operation(summary = "Current cart",
            description = "Creates an empty cart on first access. Guests must send X-Session-Id.")
    public ResponseEntity<CartResponse> get(
            @AuthenticationPrincipal UserPrincipal caller,
            @Parameter(description = "Guest session identifier")
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId) {

        return ok(carts.currentResponse(userId(caller), sessionId, RequestContext.locale()));
    }

    @PostMapping("/items")
    @Operation(summary = "Add an item",
            description = "Prices are resolved from the catalog; the request carries no price. "
                    + "Adding an item that is already present increases its quantity.")
    public ResponseEntity<CartResponse> addItem(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId,
            @Valid @RequestBody CartRequests.AddItem request) {

        return ok(carts.addItemResponse(userId(caller), sessionId, request.productId(),
                request.variantId(), request.quantity(), RequestContext.locale()));
    }

    @PatchMapping("/items/{itemId}")
    @Operation(summary = "Change a line's quantity", description = "Zero removes the line.")
    public ResponseEntity<CartResponse> updateItem(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId,
            @PathVariable UUID itemId,
            @Valid @RequestBody CartRequests.UpdateItem request) {

        return ok(carts.updateQuantityResponse(userId(caller), sessionId, itemId,
                request.quantity(), RequestContext.locale()));
    }

    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "Remove a line")
    public ResponseEntity<CartResponse> removeItem(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId,
            @PathVariable UUID itemId) {

        return ok(carts.removeItemResponse(userId(caller), sessionId, itemId, RequestContext.locale()));
    }

    @DeleteMapping
    @Operation(summary = "Empty the cart")
    public ResponseEntity<CartResponse> clear(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId) {

        return ok(carts.clearResponse(userId(caller), sessionId, RequestContext.locale()));
    }

    @PostMapping("/coupon")
    @Operation(summary = "Apply a discount code",
            description = "Advisory: the code is revalidated under a lock at checkout, so a code "
                    + "accepted here can still be refused if its last use is taken meanwhile.")
    public ResponseEntity<CartResponse> applyCoupon(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId,
            @Valid @RequestBody CartRequests.ApplyCoupon request) {

        return ok(carts.applyCouponResponse(userId(caller), sessionId, request.code(),
                RequestContext.locale()));
    }

    @DeleteMapping("/coupon")
    @Operation(summary = "Remove the discount code")
    public ResponseEntity<CartResponse> removeCoupon(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId) {

        return ok(carts.removeCouponResponse(userId(caller), sessionId, RequestContext.locale()));
    }

    @PostMapping("/merge")
    @Operation(summary = "Merge a guest cart after sign-in",
            description = "Call immediately after login. Quantities are summed; items that became "
                    + "unavailable in the meantime are skipped rather than failing the merge.")
    public ResponseEntity<CartResponse> merge(@AuthenticationPrincipal UserPrincipal caller,
                                              @Valid @RequestBody CartRequests.Merge request) {
        return ok(carts.mergeResponse(caller.id(), request.sessionId(), RequestContext.locale()));
    }

    @PostMapping("/validate")
    @Operation(summary = "Pre-checkout check",
            description = "Reports every stock and price problem at once so the customer can resolve "
                    + "them in one pass instead of one 409 at a time.")
    public ResponseEntity<CartResponse> validate(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId) {

        return ok(carts.validateResponse(userId(caller), sessionId, RequestContext.locale()));
    }

    @PostMapping("/reconcile")
    @Operation(summary = "Apply the fixes for the reported issues",
            description = "Clamps quantities to what is available, drops unavailable lines, and "
                    + "accepts current catalog prices.")
    public ResponseEntity<CartResponse> reconcile(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId) {

        return ok(carts.reconcileResponse(userId(caller), sessionId, RequestContext.locale()));
    }

    // --------------------------------------------------------------- helpers --

    /**
     * Every cart response is no-store.
     *
     * <p>A cart is per-customer and changes on every interaction; a cached one shown to the wrong
     * person, or a stale one shown to the right person, are both worse than a round trip.
     */
    private static ResponseEntity<CartResponse> ok(CartResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static UUID userId(UserPrincipal caller) {
        return caller == null ? null : caller.id();
    }
}
