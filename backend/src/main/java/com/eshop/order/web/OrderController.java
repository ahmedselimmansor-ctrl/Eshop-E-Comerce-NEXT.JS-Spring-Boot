package com.eshop.order.web;

import com.eshop.common.web.PageResponse;
import com.eshop.common.web.RequestContext;
import com.eshop.order.domain.Order;
import com.eshop.order.domain.OrderReturn;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.service.OrderService;
import com.eshop.order.web.dto.OrderResponse;
import com.eshop.order.web.dto.ReturnResponse;
import com.eshop.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A customer's own orders, plus guest order tracking.
 *
 * <p>Every response is {@code no-store}. An order contains a delivery address and a purchase
 * history; a cached copy anywhere shared is a privacy incident.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Orders")
public class OrderController {

    private final OrderService orders;

    @GetMapping("/me/orders")
    @Operation(summary = "Your orders, newest first")
    public ResponseEntity<PageResponse<OrderResponse.Summary>> list(
            @AuthenticationPrincipal UserPrincipal caller,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String locale = RequestContext.locale();

        PageResponse<OrderResponse.Summary> body = PageResponse.of(
                orders.forCustomer(caller.id(), status,
                                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 50),
                                        Sort.by(Sort.Direction.DESC, "placedAt")))
                        .map(order -> OrderResponse.summary(order, locale)));

        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/me/orders/{orderNumber}")
    @Operation(summary = "One of your orders, in full")
    public ResponseEntity<OrderResponse> detail(@AuthenticationPrincipal UserPrincipal caller,
                                                @PathVariable String orderNumber) {


        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(orders.responseFor(orderNumber, caller.id(), RequestContext.locale()));
    }

    @PostMapping("/me/orders/{orderNumber}/cancel")
    @Operation(summary = "Cancel an order",
            description = "Allowed only before dispatch. Once shipped, the route is a return. "
                    + "Releases held stock and frees any coupon that was applied.")
    public OrderResponse cancel(@AuthenticationPrincipal UserPrincipal caller,
                                @PathVariable String orderNumber,
                                @Valid @RequestBody CancelRequest request) {

        return orders.cancelByCustomerResponse(orderNumber, caller.id(), request.reason(),
                RequestContext.locale());
    }

    @PostMapping("/me/orders/{orderNumber}/returns")
    @Operation(summary = "Request a return",
            description = "Delivered orders only, inside the store's return window. Stock is not "
                    + "credited until the goods are physically received.")
    public ResponseEntity<ReturnResponse> requestReturn(@AuthenticationPrincipal UserPrincipal caller,
                                                        @PathVariable String orderNumber,
                                                        @Valid @RequestBody ReturnRequest request) {

        ReturnResponse created = orders.requestReturnResponse(orderNumber, caller.id(),
                request.quantitiesByItemId(), request.reason(), request.note());

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Guest order tracking.
     *
     * <p>Requires both the order number and the email it was placed with. The order number alone is
     * short enough to guess; pairing it with the email means a guessed number reveals nothing.
     */
    @GetMapping("/orders/track/{orderNumber}")
    @Operation(summary = "Track an order without signing in")
    @SecurityRequirements
    public ResponseEntity<OrderResponse> track(@PathVariable String orderNumber,
                                               @RequestParam String email) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(orders.trackResponse(orderNumber, email, RequestContext.locale()));
    }

    // ------------------------------------------------------------ payload types --

    public record CancelRequest(@Size(max = 500) String reason) {
    }

    public record ReturnRequest(
            @NotEmpty Map<UUID, Integer> quantitiesByItemId,
            @NotNull OrderReturn.Reason reason,
            @Size(max = 1000) String note) {
    }

}
