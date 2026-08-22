package com.eshop.order.web;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.idempotency.IdempotencyService;
import com.eshop.common.money.Money;
import com.eshop.common.web.RequestContext;
import com.eshop.order.service.CheckoutService;
import com.eshop.order.service.ShippingService;
import com.eshop.order.web.dto.CheckoutRequest;
import com.eshop.order.web.dto.OrderResponse;
import com.eshop.order.web.dto.PaymentInstructionResponse;
import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Checkout: price a prospective order, then place it.
 *
 * <p>Both guests and signed-in customers use these endpoints; a guest identifies their cart with
 * {@code X-Session-Id} and supplies an email and phone in the request.
 */
@RestController
@RequestMapping("/api/v1/checkout")
@RequiredArgsConstructor
@Tag(name = "Checkout")
@SecurityRequirements
public class CheckoutController {

    private final CheckoutService checkout;
    private final ShippingService shipping;
    private final IdempotencyService idempotency;

    @PostMapping("/quote")
    @Operation(summary = "Price a prospective order",
            description = "No side effects: nothing is reserved and no coupon is consumed. "
                    + "Call this as the customer changes address or delivery method.")
    public QuoteResponse quote(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId,
            @RequestParam String governorate,
            @RequestParam(required = false) UUID shippingMethodId,
            @RequestParam(required = false) String couponCode,
            @RequestParam(required = false) PaymentProviderType paymentProvider) {

        CheckoutService.Quote quote = checkout.quote(
                userId(caller), sessionId, governorate, shippingMethodId, couponCode, paymentProvider);

        return QuoteResponse.from(quote, RequestContext.locale());
    }

    @PostMapping("/orders")
    @Operation(summary = "Place the order",
            description = """
                    Creates the order from the caller's cart and starts its payment.

                    **Requires an `Idempotency-Key` header.** A retry with the same key returns the
                    original order rather than creating a second one — which is what makes it safe
                    for a mobile client to retry after a dropped connection.

                    Prices are re-read from the catalog inside the transaction. A price that moved
                    since the item was added returns 409 `PRICE_CHANGED` with the new figure, so the
                    customer confirms rather than being silently charged a different amount.
                    """)
    @ApiResponse(responseCode = "201", description = "Order created; `payment` says what to do next")
    @ApiResponse(responseCode = "409",
            description = "OUT_OF_STOCK, PRICE_CHANGED, COUPON_* , COD_NOT_SUPPORTED or IDEMPOTENCY_CONFLICT")
    public ResponseEntity<PlacedOrderResponse> placeOrder(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestHeader(value = RequestContext.HEADER_SESSION_ID, required = false) String sessionId,
            @Parameter(description = "Unique per checkout attempt", required = true)
            @RequestHeader(RequestContext.HEADER_IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request,
            HttpServletRequest httpRequest) {

        requireIdempotencyKey(idempotencyKey);

        UUID userId = userId(caller);
        String locale = RequestContext.locale();

        PlacedOrderResponse response = idempotency.execute(
                idempotencyKey,
                "POST /api/v1/checkout/orders",
                userId,
                request,
                PlacedOrderResponse.class,
                () -> {
                    CheckoutService.Placed placed = checkout.placeOrder(
                            userId, sessionId, request, idempotencyKey,
                            RequestContext.resolveClientIp(httpRequest),
                            httpRequest.getHeader(HttpHeaders.USER_AGENT),
                            locale);

                    return new PlacedOrderResponse(
                            OrderResponse.from(placed.order(), locale, List.of()),
                            PaymentInstructionResponse.from(placed.payment()));
                });

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/shipping-methods")
    @Operation(summary = "Delivery options for an address",
            description = "Empty list means the store does not yet deliver to that governorate.")
    public List<ShippingOption> shippingMethods(
            @RequestParam String governorate,
            @RequestParam(defaultValue = "0") java.math.BigDecimal subtotal,
            @RequestParam(defaultValue = "0") int totalGrams) {

        String locale = RequestContext.locale();
        return shipping.quotesFor(governorate, Money.of(subtotal), totalGrams).stream()
                .map(quote -> ShippingOption.from(quote, locale))
                .toList();
    }

    // --------------------------------------------------------------- helpers --

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 80) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A valid Idempotency-Key header is required")
                    .withViolation("Idempotency-Key", "REQUIRED",
                            "مطلوب مفتاح Idempotency-Key فريد لكل محاولة دفع", null);
        }
    }

    private static UUID userId(UserPrincipal caller) {
        return caller == null ? null : caller.id();
    }

    // ----------------------------------------------------------- payload types --

    public record PlacedOrderResponse(OrderResponse order, PaymentInstructionResponse payment) {
    }

    public record QuoteResponse(String currency, Money subtotal, Money discount, Money tax,
                                Money shipping, Money codFee, Money grandTotal,
                                List<ShippingOption> shippingOptions,
                                String appliedCoupon, String couponRejectionCode) {

        static QuoteResponse from(CheckoutService.Quote quote, String locale) {
            return new QuoteResponse(
                    quote.currency(), quote.subtotal(), quote.discount(), quote.tax(),
                    quote.shipping(), quote.codFee(), quote.grandTotal(),
                    quote.shippingOptions().stream()
                            .map(option -> ShippingOption.from(option, locale))
                            .toList(),
                    quote.appliedCoupon(), quote.couponRejectionCode());
        }
    }

    public record ShippingOption(UUID id, String code, String name, String description, Money cost,
                                 Short estimatedDaysMin, Short estimatedDaysMax,
                                 boolean supportsCod, boolean free, Money freeShippingThreshold) {

        static ShippingOption from(ShippingService.Quote quote, String locale) {
            return new ShippingOption(
                    quote.methodId(), quote.code(), quote.name(locale), quote.description(locale),
                    quote.cost(), quote.estimatedDaysMin(), quote.estimatedDaysMax(),
                    quote.supportsCod(), quote.isFree(), quote.freeShippingThreshold());
        }
    }
}
