package com.eshop.admin.web;

import com.eshop.admin.service.AdminOrderService;
import com.eshop.admin.web.dto.AdminOrderDetail;
import com.eshop.admin.web.dto.ReturnSummary;
import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.money.Money;
import com.eshop.common.web.PageResponse;
import com.eshop.common.web.RequestContext;
import com.eshop.order.domain.Order;
import com.eshop.order.domain.OrderReturn;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.domain.PaymentStatus;
import com.eshop.order.service.OrderService;
import com.eshop.order.web.dto.OrderResponse;
import com.eshop.payment.domain.Refund;
import com.eshop.payment.service.PaymentService;
import com.eshop.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Order administration.
 *
 * <p>Permissions are split finely on purpose. Reading orders, shipping them, and refunding them are
 * three different levels of trust: a warehouse operator needs {@code order:fulfill} and should not
 * be able to move money, which is what {@code order:refund} grants.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
@Tag(name = "Admin · Orders")
public class AdminOrderController {

    private final OrderService orders;
    private final PaymentService payments;
    private final AdminOrderService adminOrders;

    @GetMapping
    @PreAuthorize("hasAuthority('order:read')")
    @Operation(summary = "Search orders",
            description = "The free-text term matches the order number or the customer's email — "
                    + "the three things a support agent has when a customer calls.")
    public PageResponse<OrderResponse.Summary> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String locale = RequestContext.locale();

        return PageResponse.of(orders
                .adminSearch(blankToNull(q), status, paymentStatus, from, to,
                        PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100),
                                Sort.by(Sort.Direction.DESC, "placedAt")))
                .map(order -> OrderResponse.summary(order, locale)));
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAuthority('order:read')")
    @Operation(summary = "Full order, including internal notes and payment attempts")
    public AdminOrderDetail detail(@PathVariable UUID orderId) {
        return adminOrders.detail(orderId, RequestContext.locale());
    }

    @PostMapping("/{orderId}/status")
    @PreAuthorize("hasAuthority('order:write')")
    @Operation(summary = "Move the order to a new status",
            description = "Rejected with 409 INVALID_STATUS_TRANSITION if the move is not legal — "
                    + "a delivered order cannot go back to processing.")
    public OrderResponse changeStatus(@AuthenticationPrincipal UserPrincipal caller,
                                      @PathVariable UUID orderId,
                                      @Valid @RequestBody StatusChangeRequest request) {

        return adminOrders.changeStatus(orderId, request.status(), request.note(), caller.id(),
                RequestContext.locale());
    }

    @PostMapping("/{orderId}/fulfil")
    @PreAuthorize("hasAuthority('order:fulfill')")
    @Operation(summary = "Ship some or all of the order",
            description = "Quantities are checked against what is still unfulfilled, so a "
                    + "double-submitted form cannot ship more than was ordered.")
    public OrderResponse fulfil(@AuthenticationPrincipal UserPrincipal caller,
                                @PathVariable UUID orderId,
                                @Valid @RequestBody FulfilRequest request) {

        return adminOrders.fulfil(orderId, request.quantitiesByItemId(), request.carrier(),
                request.trackingNumber(), request.trackingUrl(), caller.id(),
                RequestContext.locale());
    }

    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasAuthority('order:cancel')")
    @Operation(summary = "Cancel the order",
            description = "Releases held stock, or credits it back if the sale was already committed.")
    public OrderResponse cancel(@AuthenticationPrincipal UserPrincipal caller,
                                @PathVariable UUID orderId,
                                @Valid @RequestBody CancelRequest request) {

        return adminOrders.cancel(orderId, request.reason(), caller.id(), RequestContext.locale());
    }

    @PostMapping("/{orderId}/refund")
    @PreAuthorize("hasAuthority('order:refund')")
    @Operation(summary = "Refund part or all of the order",
            description = """
                    **Requires an `Idempotency-Key` header** — a double-clicked refund button must
                    not pay out twice.

                    The amount is checked against what has already been refunded before the provider
                    is called. Over-refunding is not something a gateway will cleanly undo.
                    """)
    public RefundResponse refund(@AuthenticationPrincipal UserPrincipal caller,
                                 @PathVariable UUID orderId,
                                 @Parameter(required = true)
                                 @RequestHeader(RequestContext.HEADER_IDEMPOTENCY_KEY) String idempotencyKey,
                                 @Valid @RequestBody RefundRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "An Idempotency-Key header is required for refunds")
                    .withViolation("Idempotency-Key", "REQUIRED",
                            "مطلوب مفتاح Idempotency-Key لعمليات الاسترداد", null);
        }

        Order order = orders.require(orderId);
        Money amount = Money.of(request.amount(), order.getCurrency());

        Refund refund = payments.refund(orderId, amount, request.reason(),
                request.note(), caller.id(), idempotencyKey);

        return new RefundResponse(refund.getId(), refund.amountMoney(),
                refund.getStatus().name(), refund.getCompletedAt());
    }

    @PostMapping("/{orderId}/notes")
    @PreAuthorize("hasAuthority('order:write')")
    @Operation(summary = "Set the internal note",
            description = "Never returned by any customer-facing endpoint.")
    public ResponseEntity<Void> addNote(@AuthenticationPrincipal UserPrincipal caller,
                                        @PathVariable UUID orderId,
                                        @Valid @RequestBody NoteRequest request) {
        orders.addAdminNote(orderId, request.note(), caller.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/returns/{returnId}/resolve")
    @PreAuthorize("hasAuthority('order:write')")
    @Operation(summary = "Approve, reject, receive, or refund a return",
            description = "Stock is credited on RECEIVED, not on APPROVED — approving a return the "
                    + "customer never posts would inflate available stock.")
    public ReturnSummary resolveReturn(@AuthenticationPrincipal UserPrincipal caller,
                                       @PathVariable UUID returnId,
                                       @Valid @RequestBody ResolveReturnRequest request) {

        OrderReturn resolved = orders.resolveReturn(returnId, request.status(),
                request.adminNote(), caller.id());

        return new ReturnSummary(resolved.getId(), resolved.getReturnNumber(),
                resolved.getStatus().name(), resolved.getReason().name(), resolved.totalQuantity());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ----------------------------------------------------------- payload types --

    /**
     * @param completedAt {@code null} while the provider has not yet confirmed — the refund exists
     *                    and is counted against the refundable balance either way
     */
    public record RefundResponse(UUID id, Money amount, String status, Instant completedAt) {
    }

    public record StatusChangeRequest(@NotNull OrderStatus status, @Size(max = 500) String note) {
    }

    public record FulfilRequest(@NotEmpty Map<UUID, Integer> quantitiesByItemId,
                                @NotBlank @Size(max = 60) String carrier,
                                @Size(max = 80) String trackingNumber,
                                @Size(max = 512) String trackingUrl) {
    }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RefundRequest(@NotNull @DecimalMin("0.01") BigDecimal amount,
                                @NotBlank @Size(max = 60) String reason,
                                @Size(max = 1000) String note) {
    }

    public record NoteRequest(@Size(max = 2000) String note) {
    }

    public record ResolveReturnRequest(@NotNull OrderReturn.Status status,
                                       @Size(max = 1000) String adminNote) {
    }
}
