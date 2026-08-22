package com.eshop.order.service;

import com.eshop.cart.domain.Cart;
import com.eshop.cart.service.CartService;
import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.money.Money;
import com.eshop.config.AppProperties;
import com.eshop.inventory.service.InventoryService;
import com.eshop.order.domain.Order;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.domain.OrderStatusHistory;
import com.eshop.order.repository.OrderRepository;
import com.eshop.order.web.dto.CheckoutRequest;
import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.payment.service.PaymentService;
import com.eshop.promotion.service.CouponService;
import com.eshop.settings.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The checkout entry point: creates the order, then starts the payment.
 *
 * <p><b>Why these are two steps.</b> {@link OrderCreationService#create} runs in a database
 * transaction that holds row locks on inventory. Calling a payment gateway from inside it would
 * keep those locks — and a pooled connection — open for the gateway's entire round trip, so one
 * slow provider would serialise every checkout for the same SKU and eventually drain the pool.
 *
 * <p>The cost of splitting them is a window where an order exists with no payment. That is handled
 * rather than ignored: the order sits in {@code PENDING_PAYMENT}, and if payment initiation fails
 * the order is cancelled and its stock released immediately. If the process dies in between, the
 * reservation sweeper releases the hold after thirty minutes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final OrderCreationService orderCreation;
    private final PaymentService payments;
    private final InventoryService inventory;
    private final CouponService coupons;
    private final CartService carts;
    private final ShippingService shipping;
    private final SettingsService settings;
    private final OrderRepository orders;
    private final AppProperties properties;

    /**
     * Places an order and starts its payment.
     *
     * <p>Not annotated {@code @Transactional} on purpose — see the class comment.
     */
    public Placed placeOrder(UUID userId, String sessionId, CheckoutRequest request,
                             String idempotencyKey, String clientIp, String userAgent, String locale) {

        Order order = orderCreation.create(userId, sessionId, request, clientIp, userAgent, locale);

        try {
            PaymentService.Initiated initiated = payments.initiate(
                    order.getId(),
                    request.paymentProvider(),
                    request.paymentMethod(),
                    // Derived from the checkout key so a retried checkout reuses the same payment
                    // rather than opening a second one against the same order.
                    idempotencyKey + ":payment");

            return new Placed(order, initiated);

        } catch (RuntimeException e) {
            // The order is already committed. Roll it back explicitly so held stock is returned
            // now rather than thirty minutes from now, and the customer can retry immediately.
            log.error("payment_initiation_failed order={} provider={} — cancelling order",
                    order.getOrderNumber(), request.paymentProvider(), e);
            abandon(order.getId(), "Payment could not be started");
            throw e;
        }
    }

    /**
     * Cancels an order that never got a payment, releasing everything it was holding.
     *
     * <p>Also the path used by the sweeper for orders abandoned mid-payment.
     */
    @Transactional
    public void abandon(UUID orderId, String reason) {
        orders.findDetailById(orderId).ifPresent(order -> {
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                return;
            }
            order.transitionTo(OrderStatus.CANCELLED, reason, null, OrderStatusHistory.ActorType.SYSTEM);
            orders.save(order);

            inventory.releaseReservations(orderId);
            coupons.releaseForOrder(orderId);

            log.info("order_abandoned number={} reason={}", order.getOrderNumber(), reason);
        });
    }

    /**
     * Prices a prospective order without creating anything.
     *
     * <p>Drives the checkout summary as the customer picks an address and a delivery method. Uses
     * the same arithmetic as the real thing, but reserves no stock and redeems no coupon — so the
     * figures here are indicative, and the authoritative totals are the ones the order is created
     * with.
     */
    @Transactional(readOnly = true)
    public Quote quote(UUID userId, String sessionId, String governorate,
                       UUID shippingMethodId, String couponCode, PaymentProviderType provider) {

        Cart cart = carts.find(userId, sessionId)
                .filter(candidate -> candidate.isOwnedBy(userId, sessionId))
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_EMPTY, "No active cart"));

        if (cart.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY, "Cart has no items");
        }

        String currency = cart.getCurrency();
        Money subtotal = Money.of(cart.getSubtotal(), currency);

        CouponService.Evaluation coupon = couponCode == null || couponCode.isBlank()
                ? CouponService.Evaluation.none()
                : coupons.evaluate(couponCode, carts.priceableLines(cart), userId);

        Money discount = coupon.valid() ? coupon.discount() : Money.zero(currency);

        int totalGrams = cart.getItems().stream()
                .mapToInt(item -> {
                    int perUnit = item.getVariant() != null
                            ? item.getVariant().effectiveWeightGrams()
                            : (item.getProduct().getWeightGrams() == null
                                    ? 0 : item.getProduct().getWeightGrams());
                    return perUnit * item.getQuantity();
                })
                .sum();

        List<ShippingService.Quote> options = shipping.quotesFor(governorate, subtotal, totalGrams);

        Money shippingCost = Money.zero(currency);
        if (shippingMethodId != null) {
            shippingCost = coupon.freeShipping()
                    ? Money.zero(currency)
                    : shipping.priceFor(shippingMethodId, governorate, subtotal, totalGrams).cost();
        }

        Money codFee = provider == PaymentProviderType.COD
                ? Money.of(settings.decimal(SettingsService.CHECKOUT_COD_FEE,
                        properties.getCheckout().getCodFee()), currency)
                : Money.zero(currency);

        BigDecimal taxRate = settings.decimal(SettingsService.CHECKOUT_TAX_RATE,
                properties.getCheckout().getDefaultTaxRate());
        Money taxable = subtotal.minus(discount).atLeastZero();
        Money tax = taxable.percentage(taxRate);
        Money grandTotal = taxable.plus(tax).plus(shippingCost).plus(codFee);

        return new Quote(currency, subtotal, discount, tax, shippingCost, codFee, grandTotal,
                options, coupon.valid() ? coupon.code() : null,
                coupon.valid() ? null : (coupon.reason() == null ? null : coupon.reason().name()));
    }

    /** An order plus everything the client needs to complete its payment. */
    public record Placed(Order order, PaymentService.Initiated payment) {
    }

    /**
     * Indicative totals for the checkout summary.
     *
     * @param couponRejectionCode why an applied code was refused, or {@code null}
     */
    public record Quote(String currency,
                        Money subtotal,
                        Money discount,
                        Money tax,
                        Money shipping,
                        Money codFee,
                        Money grandTotal,
                        List<ShippingService.Quote> shippingOptions,
                        String appliedCoupon,
                        String couponRejectionCode) {
    }
}
