package com.eshop.payment.service;

import com.eshop.common.money.Money;
import com.eshop.payment.domain.PaymentMethod;
import com.eshop.payment.domain.PaymentProviderType;

import java.util.Map;
import java.util.UUID;

/**
 * The contract every payment gateway integration implements.
 *
 * <p>Adding a gateway means writing one implementation of this interface and registering it as a
 * Spring bean — {@code PaymentService} discovers providers by type and never mentions a specific
 * one. That is what keeps checkout, refunds, and reconciliation free of
 * {@code if (provider == PAYMOB)} branches.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>never persist, log, or return card numbers, CVVs, or expiry dates;</li>
 *   <li>redact their raw payloads through {@code PaymentPayloadRedactor} before handing them back;</li>
 *   <li>verify callback signatures in constant time and treat an unverified callback as hostile;</li>
 *   <li>be safe to call twice for the same order — the caller retries on network failures.</li>
 * </ul>
 */
public interface PaymentProvider {

    PaymentProviderType type();

    /** Whether credentials are present and the provider is enabled for this environment. */
    boolean isConfigured();

    boolean supports(PaymentMethod method);

    /**
     * Starts a payment.
     *
     * <p>What the customer does next differs per provider — open an iframe, memorise a reference
     * code, or simply wait for the courier — which is why {@link Initiation} carries all three
     * shapes and the caller renders whichever is populated.
     *
     * @throws com.eshop.common.exception.BusinessException {@code PAYMENT_PROVIDER_ERROR} when the
     *         gateway is unreachable or rejects the request
     */
    Initiation initiate(PaymentRequest request);

    /**
     * Re-reads a payment's status from the provider.
     *
     * <p>The safety net for a lost webhook: the storefront polls this after the customer returns,
     * and a scheduled reconciliation job uses it to settle payments whose callback never arrived.
     */
    Verification verify(String providerReference);

    /**
     * Parses and authenticates a callback.
     *
     * <p>Returns {@link Callback#invalid} rather than throwing on a bad signature, so the caller can
     * still persist the attempt for investigation before rejecting it.
     */
    Callback parseCallback(String rawBody, Map<String, String> headers);

    /**
     * Sends money back.
     *
     * @param amount partial or full; the caller has already checked it against the captured total
     */
    RefundResult refund(String providerPaymentId, Money amount, String reason);

    /**
     * @param orderId       our order id, echoed by the provider so callbacks can be matched
     * @param orderNumber   the human-readable reference shown to the customer
     * @param amount        total to charge
     * @param method        the customer's chosen method
     * @param customerEmail receipt destination
     * @param billingData   name, phone, and address fields the provider requires
     * @param returnUrl     where the provider sends the browser afterwards
     * @param locale        {@code ar} or {@code en} for the hosted page
     */
    record PaymentRequest(UUID orderId,
                          String orderNumber,
                          Money amount,
                          PaymentMethod method,
                          String customerEmail,
                          String customerPhone,
                          String customerName,
                          Map<String, Object> billingData,
                          String returnUrl,
                          String locale) {
    }

    /**
     * How to complete the payment, plus whatever the provider assigned it.
     *
     * @param iframeUrl     Paymob: the hosted page to embed or redirect to
     * @param redirectUrl   an off-site page to send the browser to
     * @param referenceCode Fawry: the number the customer quotes at an outlet
     * @param expiresAt     when the reference or session lapses
     * @param immediatelySettled true for cash on delivery, which has nothing to wait for
     */
    record Initiation(String providerPaymentId,
                      String providerOrderId,
                      String iframeUrl,
                      String redirectUrl,
                      String referenceCode,
                      java.time.Instant expiresAt,
                      boolean immediatelySettled,
                      Map<String, Object> rawResponse) {
    }

    /** Current provider-side state of a payment. */
    record Verification(Status status,
                        String providerPaymentId,
                        Money amount,
                        String cardBrand,
                        String cardLast4,
                        String failureCode,
                        String failureReason,
                        Map<String, Object> rawResponse) {
    }

    /**
     * A parsed callback.
     *
     * @param eventId   stable identifier for the delivery, used for the idempotency constraint
     * @param reference the provider identifier that maps back to one of our payments
     * @param valid     whether the signature verified — {@code false} means do not act on it
     */
    record Callback(boolean valid,
                    String eventId,
                    String eventType,
                    String reference,
                    Status status,
                    Money amount,
                    String cardBrand,
                    String cardLast4,
                    String failureCode,
                    String failureReason,
                    Map<String, Object> payload) {

        public static Callback invalid(String eventId, Map<String, Object> payload) {
            return new Callback(false, eventId, null, null, Status.UNKNOWN,
                    null, null, null, null, null, payload);
        }
    }

    record RefundResult(boolean succeeded, String providerRefundId, String failureReason,
                        Map<String, Object> rawResponse) {
    }

    /** Provider-neutral payment outcome. */
    enum Status {
        PENDING,
        AUTHORIZED,
        CAPTURED,
        FAILED,
        CANCELLED,
        EXPIRED,
        REFUNDED,
        /** The provider reported something this integration does not recognise. */
        UNKNOWN
    }
}
