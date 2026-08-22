package com.eshop.order.web.dto;

import com.eshop.common.money.Money;
import com.eshop.order.domain.FulfillmentStatus;
import com.eshop.order.domain.Order;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.domain.PaymentStatus;
import com.eshop.order.domain.Shipment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An order as the customer sees it.
 *
 * <p>{@code adminNote} is deliberately absent - internal remarks about a customer must never reach
 * a customer-facing endpoint by accident. The admin projection adds it explicitly.
 */
@Schema(name = "OrderResponse")
public record OrderResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        PaymentStatus paymentStatus,
        FulfillmentStatus fulfillmentStatus,
        String currency,
        List<Item> items,
        Totals totals,
        String couponCode,

        @Schema(description = "Address frozen at placement, not the customer's current address book")
        Map<String, Object> shippingAddress,

        Map<String, Object> billingAddress,
        String shippingMethodName,
        String customerNote,
        List<TimelineEntry> timeline,
        List<ShipmentResponse> shipments,

        @Schema(description = "Whether the customer can still cancel this themselves")
        boolean cancellable,

        Instant placedAt,
        Instant paidAt,
        Instant shippedAt,
        Instant deliveredAt,
        Instant cancelledAt
) {

    public record Item(UUID id, UUID productId, UUID variantId, String slug, String sku,
                       String name, String variantLabel, String imageUrl,
                       Money unitPrice, int quantity, Money discount, Money tax, Money lineTotal,
                       int fulfilledQuantity, int refundedQuantity) {
    }

    public record Totals(Money subtotal, Money discount, Money tax, Money shipping,
                         Money codFee, Money grandTotal) {
    }

    public record TimelineEntry(OrderStatus status, String note, Instant at, String by) {
    }

    public record ShipmentResponse(UUID id, String carrier, String trackingNumber, String trackingUrl,
                                   String status, Instant shippedAt, Instant deliveredAt) {
    }

    public static OrderResponse from(Order order, String locale, List<Shipment> shipments) {
        String currency = order.getCurrency();

        List<Item> items = order.getItems().stream()
                .map(item -> new Item(
                        item.getId(),
                        item.getProduct() == null ? null : item.getProduct().getId(),
                        item.getVariant() == null ? null : item.getVariant().getId(),
                        item.getProduct() == null ? null : item.getProduct().getSlug(),
                        item.getSku(),
                        item.name(locale),
                        item.variantLabel(locale),
                        item.getImageUrl(),
                        item.unitPriceMoney(currency),
                        item.getQuantity(),
                        Money.of(item.getDiscountAmount(), currency),
                        Money.of(item.getTaxAmount(), currency),
                        item.lineTotalMoney(currency),
                        item.getFulfilledQuantity(),
                        item.getRefundedQuantity()))
                .toList();

        List<TimelineEntry> timeline = order.getHistory().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.getCreatedAt()))
                .map(entry -> new TimelineEntry(
                        entry.getToStatus(),
                        entry.getNote(),
                        entry.getCreatedAt(),
                        entry.getChangedByType().name()))
                .toList();

        List<ShipmentResponse> shipmentResponses = shipments.stream()
                .map(shipment -> new ShipmentResponse(
                        shipment.getId(),
                        shipment.getCarrier(),
                        shipment.getTrackingNumber(),
                        shipment.getTrackingUrl(),
                        shipment.getStatus().name(),
                        shipment.getShippedAt(),
                        shipment.getDeliveredAt()))
                .toList();

        boolean english = "en".equalsIgnoreCase(locale);

        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getFulfillmentStatus(),
                currency,
                items,
                new Totals(
                        Money.of(order.getSubtotal(), currency),
                        Money.of(order.getDiscountTotal(), currency),
                        Money.of(order.getTaxTotal(), currency),
                        Money.of(order.getShippingTotal(), currency),
                        Money.of(order.getCodFee(), currency),
                        Money.of(order.getGrandTotal(), currency)),
                order.getCouponCode(),
                order.getShippingAddress(),
                order.getBillingAddress(),
                english && order.getShippingMethodNameEn() != null
                        ? order.getShippingMethodNameEn() : order.getShippingMethodNameAr(),
                order.getCustomerNote(),
                timeline,
                shipmentResponses,
                order.getStatus().isCancellableByCustomer(),
                order.getPlacedAt(),
                order.getPaidAt(),
                order.getShippedAt(),
                order.getDeliveredAt(),
                order.getCancelledAt());
    }

    /** Compact projection for the order list, where line detail and timeline are noise. */
    public static Summary summary(Order order, String locale) {
        String currency = order.getCurrency();
        return new Summary(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.totalQuantity(),
                Money.of(order.getGrandTotal(), currency),
                order.getItems().stream().findFirst().map(item -> item.getImageUrl()).orElse(null),
                order.getItems().stream().findFirst().map(item -> item.name(locale)).orElse(null),
                order.getStatus().isCancellableByCustomer(),
                order.getPlacedAt());
    }

    @Schema(name = "OrderSummary")
    public record Summary(UUID id, String orderNumber, OrderStatus status, PaymentStatus paymentStatus,
                          int itemCount, Money grandTotal, String previewImageUrl, String previewName,
                          boolean cancellable, Instant placedAt) {
    }
}
