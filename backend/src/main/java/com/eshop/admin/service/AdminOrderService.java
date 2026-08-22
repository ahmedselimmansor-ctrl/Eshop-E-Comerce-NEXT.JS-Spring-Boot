package com.eshop.admin.service;

import com.eshop.admin.web.dto.AdminOrderDetail;
import com.eshop.admin.web.dto.PaymentAttempt;
import com.eshop.admin.web.dto.ReturnSummary;
import com.eshop.order.domain.Order;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.service.OrderService;
import com.eshop.order.web.dto.OrderResponse;
import com.eshop.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Order reads and mutations for the console, mapped inside the transaction that produced them.
 *
 * <p>An {@code Order} is the worst offender for lazy state in this codebase: {@code OrderResponse}
 * walks items, their product references, the status timeline, and the shipment list. Mapping it in a
 * controller — after the service's transaction has committed and the session is gone — throws
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

    @Transactional
    public OrderResponse fulfil(UUID orderId, Map<UUID, Integer> quantities, String carrier,
                                String trackingNumber, String trackingUrl, UUID actorId,
                                String locale) {

        orders.fulfil(orderId, quantities, carrier, trackingNumber, trackingUrl, actorId);

        Order order = orders.require(orderId);
        return OrderResponse.from(order, locale, orders.shipmentsOf(orderId));
    }
}
