package com.eshop.payment.service.provider;

import com.eshop.common.money.Money;
import com.eshop.config.AppProperties;
import com.eshop.payment.domain.PaymentMethod;
import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.payment.service.PaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Cash on delivery.
 *
 * <p>There is no gateway here, which makes the semantics unusual and worth stating: the payment is
 * created {@code PENDING} and stays that way until the courier hands over the goods and takes the
 * money. {@code Order.transitionTo(DELIVERED)} is what settles it.
 *
 * <p>Deliberately <em>not</em> marked {@code immediatelySettled}. Treating COD as paid at checkout
 * would commit the stock reservation and recognise revenue for an order that may well be refused at
 * the door - a routine outcome for COD, not an edge case.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodPaymentProvider implements PaymentProvider {

    private final AppProperties properties;

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.COD;
    }

    @Override
    public boolean isConfigured() {
        return properties.getPayment().getCod().isEnabled();
    }

    @Override
    public boolean supports(PaymentMethod method) {
        return method == PaymentMethod.COD;
    }

    @Override
    public Initiation initiate(PaymentRequest request) {
        log.info("cod_payment_created order={} amount={}", request.orderNumber(), request.amount());

        return new Initiation(
                null,
                request.orderId().toString(),
                null,
                null,
                null,
                // No expiry: the order is fulfilled and collected on the normal delivery timeline.
                null,
                false,
                Map.of("provider", "COD", "collectOnDelivery", true));
    }

    /**
     * COD has no external system to ask. The authoritative state is our own order status, so this
     * always reports {@code PENDING} and the caller reads the order instead.
     */
    @Override
    public Verification verify(String providerReference) {
        return new Verification(Status.PENDING, providerReference, null, null, null, null, null, Map.of());
    }

    @Override
    public Callback parseCallback(String rawBody, Map<String, String> headers) {
        // Nothing can legitimately call back for a cash payment; treating any attempt as invalid
        // means a forged "COD paid" callback cannot settle an order.
        return Callback.invalid(null, Map.of());
    }

    /**
     * A COD refund is a cash or transfer handover recorded by finance, not an API call. The refund
     * row is created and marked complete so the order's accounting is correct; moving the money is
     * an offline step.
     */
    @Override
    public RefundResult refund(String providerPaymentId, Money amount, String reason) {
        log.info("cod_refund_recorded amount={} reason={} - settle manually", amount, reason);
        return new RefundResult(true, null, null,
                Map.of("provider", "COD", "manualSettlement", true, "amount", amount.amount().toPlainString()));
    }

    /** Whether an order is small enough to risk sending out uncollected. */
    public boolean isWithinLimit(Money amount) {
        return amount.amount().compareTo(properties.getPayment().getCod().getMaxOrderAmount()) <= 0;
    }
}
