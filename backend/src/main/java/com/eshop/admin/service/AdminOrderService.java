package com.eshop.admin.service;

import com.eshop.admin.web.dto.AdminOrderDetail;
import com.eshop.admin.web.dto.PaymentAttempt;
import com.eshop.admin.web.dto.RefundResponse;
import com.eshop.admin.web.dto.ReturnSummary;
import com.eshop.order.domain.Order;
import com.eshop.order.domain.OrderReturn;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.service.OrderService;
import com.eshop.order.web.dto.OrderResponse;
import com.eshop.common.money.Money;
import com.eshop.payment.domain.Refund;
import com.eshop.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Order reads and mutations for the console, mapped inside the transaction that produced them.
 *
 * <p>An {@code Order} is the worst offender for lazy state in this codebase: {@code OrderResponse}
 * walks items, their product references, the status timeline, and the shipment list. Mapping it in a
 * controller - after the service's transaction has committed and the session is gone - throws
 * {@code LazyInitializationException} on every call while compiling perfectly well.
 *
 * <p>See {@link AdminOpsService} for the same reasoning applied to the rest of the console.
 */
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private final OrderService orders;
    private final PaymentService payments;

    /** The console's order screen: the customer's view plus what is deliberately withheld from it. */
    @Transactional(readOnly = true)
    public AdminOrderDetail detail(UUID orderId, String locale) {
        Order order = orders.require(orderId);

        return new AdminOrderDetail(
                OrderResponse.from(order, locale, orders.shipmentsOf(orderId)),
                order.getAdminNote(),
                order.getIp(),
                order.getUserAgent(),
                payments.forOrder(orderId).stream()
                        .map(payment -> new PaymentAttempt(
                                payment.getId(),
                                payment.getProvider().name(),
                                payment.getMethod() == null ? null : payment.getMethod().name(),
                                payment.getStatus().name(),
                                payment.amountMoney(),
                                payment.getCardBrand(),
                                payment.getCardLast4(),
                                payment.getFailureReason(),
                                payment.getCreatedAt()))
                        .toList(),
                orders.returnsForOrder(orderId).stream()
                        .map(orderReturn -> new ReturnSummary(
                                orderReturn.getId(),
                                orderReturn.getReturnNumber(),
                                orderReturn.getStatus().name(),
                                orderReturn.getReason() == null ? null : orderReturn.getReason().name(),
                                orderReturn.totalQuantity()))
                        .toList());
    }

    @Transactional
    public OrderResponse changeStatus(UUID orderId, OrderStatus status, String note, UUID actorId,
                                      String locale) {
        Order order = orders.changeStatus(orderId, status, note, actorId);
        return OrderResponse.from(order, locale, orders.shipmentsOf(orderId));
    }

    @Transactional
    public OrderResponse cancel(UUID orderId, String reason, UUID actorId, String locale) {
        Order order = orders.cancelByAdmin(orderId, reason, actorId);
        return OrderResponse.from(order, locale, orders.shipmentsOf(orderId));
    }

    /**
     * Refunds, mapped inside the transaction.
     *
     * <p>{@link com.eshop.payment.domain.Refund#amountMoney()} reads the currency off its payment,
     * and on the replay path the refund is loaded fresh with that association still a proxy. Mapping
     * in the controller therefore worked on the first call, where the payment was already in the
     * session, and threw {@code LazyInitializationException} on every retry - which is precisely the
     * case idempotency exists to serve.
     */
    @Transactional
    public RefundResponse refund(UUID orderId, BigDecimal requested, String reason, String note,
                                 UUID actorId, String idempotencyKey) {

        // The currency comes from the order, so the caller cannot refund in one the sale was not
        // made in. Looked up here rather than in the controller so no entity crosses that boundary.
        Order order = orders.require(orderId);
        Money amount = Money.of(requested, order.getCurrency());

        Refund refund = payments.refund(orderId, amount, reason, note, actorId, idempotencyKey);

        return new RefundResponse(refund.getId(), refund.amountMoney(),
                refund.getStatus().name(), refund.getCompletedAt());
    }

    /** Approves or rejects a return, mapped inside the transaction like everything else here. */
    @Transactional
    public ReturnSummary resolveReturn(UUID returnId, OrderReturn.Status status, String adminNote,
                                       UUID actorId) {

        OrderReturn resolved = orders.resolveReturn(returnId, status, adminNote, actorId);

        return new ReturnSummary(
                resolved.getId(),
                resolved.getReturnNumber(),
                resolved.getStatus().name(),
                resolved.getReason() == null ? null : resolved.getReason().name(),
                resolved.totalQuantity());
    }

    @Transactional
    public OrderResponse fulfil(UUID orderId, Map<UUID, Integer> quantities, String carrier,
                                String trackingNumber, String trackingUrl, UUID actorId,
                                String locale) {

        orders.fulfil(orderId, quantities, carrier, trackingNumber, trackingUrl, actorId);

        Order order = orders.require(orderId);
        return OrderResponse.from(order, locale, orders.shipmentsOf(orderId));
    }
}
