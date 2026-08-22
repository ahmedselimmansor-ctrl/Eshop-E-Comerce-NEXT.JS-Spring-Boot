package com.eshop.order.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.common.outbox.OutboxEvent;
import com.eshop.common.outbox.OutboxService;
import com.eshop.inventory.service.InventoryService;
import com.eshop.order.domain.Order;
import com.eshop.order.domain.OrderItem;
import com.eshop.order.domain.OrderReturn;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.domain.OrderStatusHistory;
import com.eshop.order.domain.ReturnItem;
import com.eshop.order.domain.Shipment;
import com.eshop.order.domain.ShipmentItem;
import com.eshop.order.repository.OrderRepository;
import com.eshop.order.repository.OrderItemRepository;
import com.eshop.order.repository.ReturnRepository;
import com.eshop.order.repository.ShipmentRepository;
import com.eshop.order.domain.PaymentStatus;
import com.eshop.promotion.service.CouponService;
import com.eshop.settings.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Order operations after checkout: retrieval, cancellation, fulfilment, and returns.
 *
 * <p>Every status change goes through {@link Order#transitionTo}, which enforces the state machine
 * and writes a history row. Nothing here assigns {@code status} directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final ShipmentRepository shipments;
    private final ReturnRepository returns;
    private final InventoryService inventory;
    private final CouponService coupons;
    private final SettingsService settings;
    private final OutboxService outbox;

    // ------------------------------------------------------------- retrieval --

    @Transactional(readOnly = true)
    public Order require(UUID orderId) {
        return orders.findDetailById(orderId)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
    }

    /**
     * Loads an order only if the caller may see it.
     *
     * <p>Raises 404 rather than 403 on a mismatch: order numbers are short and sequential enough
     * that a distinguishable "forbidden" would let anyone enumerate how many orders the store has
     * taken.
     */
    @Transactional(readOnly = true)
    public Order requireVisible(String orderNumber, UUID userId, String guestEmail) {
        Order order = orders.findDetailByOrderNumber(orderNumber)
                .orElseThrow(() -> NotFoundException.of("Order", orderNumber));

        if (!order.isVisibleTo(userId, guestEmail)) {
            throw NotFoundException.of("Order", orderNumber);
        }
        return order;
    }

    /**
     * The customer's order history.
     *
     * <p>{@code items} is not in the query's entity graph - a join-fetched collection would stop
     * Hibernate paging in SQL - so it is initialised here, inside the transaction, before the
     * summaries are built. {@code @BatchSize} on the collection makes that one extra query for the
     * whole page rather than one per order.
     */
    @Transactional(readOnly = true)
    public Page<Order> forCustomer(UUID userId, OrderStatus status, Pageable pageable) {
        Page<Order> page = orders.findForCustomer(userId, status, pageable);
        page.forEach(order -> order.getItems().size());
        return page;
    }

    /**
     * The admin order list, and the dashboard's recent-orders feed.
     *
     * <p>Same reason as {@link #forCustomer} for touching {@code items}: the summary renders a
     * preview taken from the first line, and the collection is deliberately outside the query's
     * entity graph so the query can page in SQL.
     */
    @Transactional(readOnly = true)
    public Page<Order> adminSearch(String query, OrderStatus status, PaymentStatus paymentStatus,
                                   Instant from, Instant to, Pageable pageable) {

        Page<Order> page = orders.adminSearch(query, status, paymentStatus, from, to, pageable);
        page.forEach(order -> order.getItems().size());
        return page;
    }

    @Transactional(readOnly = true)
    public List<Shipment> shipmentsOf(UUID orderId) {
        return shipments.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    // ---------------------------------------------------------- cancellation --

    /**
     * Customer-initiated cancellation.
     *
     * <p>Allowed only before dispatch. Once a parcel is with a courier, the customer's route is a
     * return, not a cancellation - cancelling would leave stock credited for goods that are still
     * in transit.
     */
    @Transactional
    public Order cancelByCustomer(String orderNumber, UUID userId, String reason) {
        Order order = requireVisible(orderNumber, userId, null);

        if (!order.getStatus().isCancellableByCustomer()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "Order is " + order.getStatus() + " and can no longer be cancelled")
                    .withProperty("status", order.getStatus().name());
        }
        return cancel(order, reason, userId, OrderStatusHistory.ActorType.CUSTOMER);
    }

    @Transactional
    public Order cancelByAdmin(UUID orderId, String reason, UUID adminId) {
        return cancel(require(orderId), reason, adminId, OrderStatusHistory.ActorType.ADMIN);
    }

    private Order cancel(Order order, String reason, UUID actorId, OrderStatusHistory.ActorType actorType) {
        boolean wasHoldingStock = order.getStatus().holdsReservation();

        order.transitionTo(OrderStatus.CANCELLED, reason, actorId, actorType);
        orders.save(order);

        if (wasHoldingStock) {
            // Still only reserved - hand it straight back.
            inventory.releaseReservations(order.getId());
        } else {
            // Already committed as a sale, so the units have to be credited back explicitly.
            order.getItems().forEach(item -> {
                if (item.getProduct() != null) {
                    inventory.restock(item.getProduct().getId(),
                            item.getVariant() == null ? null : item.getVariant().getId(),
                            item.getQuantity() - item.getRefundedQuantity(),
                            order.getId(), actorId);
                }
            });
        }

        coupons.releaseForOrder(order.getId());

        outbox.record("Order", order.getId(), OutboxEvent.ORDER_CANCELLED,
                "orderId", order.getId().toString(),
                "orderNumber", order.getOrderNumber(),
                "email", order.contactEmail(),
                "reason", reason == null ? "" : reason);

        log.info("order_cancelled number={} actor={} reason={}", order.getOrderNumber(), actorId, reason);
        return order;
    }

    // ------------------------------------------------------------ fulfilment --

    /**
     * Dispatches some or all of an order.
     *
     * <p>Quantities are validated against what remains unfulfilled, so a double-submitted
     * fulfilment cannot ship more units than were ordered.
     */
    @Transactional
    public Shipment fulfil(UUID orderId, Map<UUID, Integer> quantitiesByItemId,
                           String carrier, String trackingNumber, String trackingUrl, UUID adminId) {

        Order order = require(orderId);

        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.CONFLICT, "Cannot ship an unpaid order");
        }

        Shipment shipment = new Shipment();
        shipment.setOrder(order);
        shipment.setCarrier(carrier);
        shipment.setTrackingNumber(trackingNumber);
        shipment.setTrackingUrl(trackingUrl);

        List<OrderItem> items = orderItems.findForOrder(
                List.copyOf(quantitiesByItemId.keySet()), orderId);

        if (items.size() != quantitiesByItemId.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "One or more items do not belong to this order");
        }

        for (OrderItem item : items) {
            int quantity = quantitiesByItemId.get(item.getId());
            if (quantity <= 0 || quantity > item.remainingToFulfil()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Invalid fulfilment quantity for item " + item.getId())
                        .withViolation("items[" + item.getId() + "]", "INVALID_QUANTITY",
                                "الكمية المتبقية للشحن " + item.remainingToFulfil(), quantity);
            }

            ShipmentItem shipmentItem = new ShipmentItem();
            shipmentItem.setOrderItem(item);
            shipmentItem.setQuantity(quantity);
            shipment.addItem(shipmentItem);

            item.setFulfilledQuantity(item.getFulfilledQuantity() + quantity);
        }

        shipment.markShipped();
        shipments.save(shipment);

        order.refreshFulfillmentStatus();
        if (order.getStatus().canTransitionTo(OrderStatus.SHIPPED)) {
            order.transitionTo(OrderStatus.SHIPPED,
                    "Shipped via " + carrier + (trackingNumber == null ? "" : " (" + trackingNumber + ")"),
                    adminId, OrderStatusHistory.ActorType.ADMIN);
        }
        orders.save(order);

        outbox.record("Order", order.getId(), OutboxEvent.ORDER_SHIPPED,
                "orderId", order.getId().toString(),
                "orderNumber", order.getOrderNumber(),
                "email", order.contactEmail(),
                "carrier", carrier == null ? "" : carrier,
                "trackingNumber", trackingNumber == null ? "" : trackingNumber);

        log.info("order_fulfilled number={} shipment={} items={}",
                order.getOrderNumber(), shipment.getId(), items.size());
        return shipment;
    }

    @Transactional
    public Order changeStatus(UUID orderId, OrderStatus target, String note, UUID adminId) {
        Order order = require(orderId);
        order.transitionTo(target, note, adminId, OrderStatusHistory.ActorType.ADMIN);
        orders.save(order);

        if (target == OrderStatus.DELIVERED) {
            outbox.record("Order", order.getId(), OutboxEvent.ORDER_DELIVERED,
                    "orderId", order.getId().toString(),
                    "orderNumber", order.getOrderNumber(),
                    "email", order.contactEmail());
        }

        log.info("order_status_changed number={} to={} actor={}", order.getOrderNumber(), target, adminId);
        return order;
    }

    @Transactional
    public void addAdminNote(UUID orderId, String note, UUID adminId) {
        Order order = require(orderId);
        order.setAdminNote(note);
        orders.save(order);
        log.info("order_note_added number={} actor={}", order.getOrderNumber(), adminId);
    }

    // ---------------------------------------------------------------- returns --

    /**
     * Opens a return request.
     *
     * <p>Bounded by the store's return window and by how much of each line has not already been
     * returned, so a customer cannot return the same unit twice across two requests.
     */
    @Transactional
    public OrderReturn requestReturn(String orderNumber, UUID userId,
                                     Map<UUID, Integer> quantitiesByItemId,
                                     OrderReturn.Reason reason, String note) {

        Order order = requireVisible(orderNumber, userId, null);

        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only delivered orders can be returned")
                    .withProperty("status", order.getStatus().name());
        }

        int windowDays = settings.integer(SettingsService.ORDER_RETURN_WINDOW_DAYS, 14);
        Instant deadline = order.getDeliveredAt().plus(windowDays, ChronoUnit.DAYS);
        if (Instant.now().isAfter(deadline)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Return window has closed")
                    .withViolation("orderNumber", "RETURN_WINDOW_CLOSED",
                            "انتهت مهلة الإرجاع (" + windowDays + " يوم)", orderNumber);
        }

        OrderReturn orderReturn = new OrderReturn();
        orderReturn.setOrder(order);
        orderReturn.setUser(order.getUser());
        orderReturn.setReturnNumber(orders.nextReturnNumber());
        orderReturn.setReason(reason);
        orderReturn.setNote(note);

        List<OrderItem> items = orderItems.findForOrder(
                List.copyOf(quantitiesByItemId.keySet()), order.getId());

        for (OrderItem item : items) {
            int requested = quantitiesByItemId.get(item.getId());
            int alreadyReturned = returns.quantityAlreadyReturned(item.getId());
            int returnable = item.getQuantity() - alreadyReturned;

            if (requested <= 0 || requested > returnable) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Invalid return quantity for item " + item.getId())
                        .withViolation("items[" + item.getId() + "]", "INVALID_QUANTITY",
                                "الكمية القابلة للإرجاع " + returnable, requested);
            }

            ReturnItem returnItem = new ReturnItem();
            returnItem.setOrderItem(item);
            returnItem.setQuantity(requested);
            orderReturn.addItem(returnItem);
        }

        returns.save(orderReturn);
        log.info("return_requested order={} return={} items={}",
                orderNumber, orderReturn.getReturnNumber(), items.size());
        return orderReturn;
    }

    @Transactional
    public OrderReturn resolveReturn(UUID returnId, OrderReturn.Status target,
                                     String adminNote, UUID adminId) {

        OrderReturn orderReturn = returns.findDetailById(returnId)
                .orElseThrow(() -> NotFoundException.of("Return", returnId));

        switch (target) {
            case APPROVED -> orderReturn.approve(adminId, adminNote);
            case REJECTED -> orderReturn.reject(adminId, adminNote);
            case RECEIVED -> {
                orderReturn.markReceived(adminId);
                restockReceived(orderReturn, adminId);
            }
            case REFUNDED -> orderReturn.markRefunded();
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Unsupported return status " + target);
        }

        returns.save(orderReturn);
        log.info("return_resolved return={} status={} actor={}",
                orderReturn.getReturnNumber(), target, adminId);
        return orderReturn;
    }

    /**
     * Credits stock for goods physically received back.
     *
     * <p>Only resellable units go back on the shelf; anything marked used or damaged is left off
     * so it cannot be sold to the next customer.
     */
    private void restockReceived(OrderReturn orderReturn, UUID adminId) {
        orderReturn.getItems().stream()
                .filter(ReturnItem::isResellable)
                .forEach(item -> {
                    OrderItem orderItem = item.getOrderItem();
                    if (orderItem.getProduct() == null) {
                        return;
                    }
                    inventory.restock(
                            orderItem.getProduct().getId(),
                            orderItem.getVariant() == null ? null : orderItem.getVariant().getId(),
                            item.getQuantity(),
                            orderReturn.getId(),
                            adminId);
                });
    }

    @Transactional(readOnly = true)
    public List<OrderReturn> returnsForOrder(UUID orderId) {
        return returns.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    // ---------------------------------------------------------------- sweeper --

    /**
     * Cancels orders that were never paid, releasing their stock.
     *
     * <p>Run on a schedule under a cluster-wide lock. The window comes from
     * {@code order.auto_cancel_minutes} so an operator can widen it during a payment-provider
     * incident without a deploy.
     */
    @Transactional
    public int cancelStaleUnpaid(int batchSize) {
        int minutes = settings.integer(SettingsService.ORDER_AUTO_CANCEL_MINUTES, 30);
        Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES);

        List<Order> stale = orders.findStaleUnpaid(cutoff, PageRequest.of(0, batchSize));

        stale.forEach(order -> {
            order.transitionTo(OrderStatus.CANCELLED,
                    "Payment not completed within " + minutes + " minutes",
                    null, OrderStatusHistory.ActorType.SYSTEM);
            orders.save(order);
            inventory.releaseReservations(order.getId());
            coupons.releaseForOrder(order.getId());
        });

        if (!stale.isEmpty()) {
            log.info("stale_unpaid_orders_cancelled count={} cutoff_minutes={}", stale.size(), minutes);
        }
        return stale.size();
    }
}
