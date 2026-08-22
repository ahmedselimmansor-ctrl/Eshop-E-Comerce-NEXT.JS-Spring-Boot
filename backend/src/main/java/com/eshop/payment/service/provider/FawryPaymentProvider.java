package com.eshop.payment.service.provider;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.money.Money;
import com.eshop.common.util.Hashes;
import com.eshop.config.AppProperties;
import com.eshop.payment.domain.PaymentMethod;
import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.payment.service.PaymentPayloadRedactor;
import com.eshop.payment.service.PaymentProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fawry integration - reference-code payments settled in cash.
 *
 * <p>Fawry matters here because most online shoppers in this market do not use a card. The customer
 * checks out, receives a numeric reference, and pays it at any Fawry outlet, ATM, or through a
 * banking app. Settlement is asynchronous and can be hours or days later, which shapes the whole
 * integration: the order sits in {@code PENDING_PAYMENT} with stock <em>released</em> after the
 * normal window, and payment confirmation arrives by callback long after the browser has gone.
 *
 * <p>Both the request and the callback are authenticated by a SHA-256 digest over a specific
 * concatenation ending in the merchant secret. The field order is dictated by Fawry and is not
 * negotiable - see {@link #chargeSignature} and {@link #callbackSignature}.
 *
 * <p>Amounts are sent as decimal strings with exactly two places. Fawry rejects {@code 1299} where
 * {@code 1299.00} was expected, and the signature is computed over the formatted string, so any
 * difference in formatting breaks authentication rather than merely the amount.
 */
@Slf4j
@Component
public class FawryPaymentProvider implements PaymentProvider {

    private static final String PAY_AT_FAWRY = "PAYATFAWRY";

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public FawryPaymentProvider(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getPayment().getFawry().getBaseUrl())
                .build();
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.FAWRY;
    }

    @Override
    public boolean isConfigured() {
        return config().isConfigured();
    }

    @Override
    public boolean supports(PaymentMethod method) {
        return method == PaymentMethod.KIOSK;
    }

    // ------------------------------------------------------------- initiate --

    @Override
    public Initiation initiate(PaymentRequest request) {
        requireConfigured();
        AppProperties.Payment.Fawry fawry = config();

        String amount = formatAmount(request.amount().amount());
        String merchantRefNum = request.orderNumber();
        String customerProfileId = "";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantCode", fawry.getMerchantCode());
        body.put("merchantRefNum", merchantRefNum);
        body.put("customerProfileId", customerProfileId);
        body.put("customerName", request.customerName());
        body.put("customerMobile", request.customerPhone());
        body.put("customerEmail", request.customerEmail());
        body.put("paymentMethod", PAY_AT_FAWRY);
        body.put("amount", new BigDecimal(amount));
        body.put("currencyCode", request.amount().currency());
        body.put("description", "Order " + merchantRefNum);
        body.put("paymentExpiry", Instant.now()
                .plusSeconds(fawry.getExpiryHours() * 3600L).toEpochMilli());
        body.put("chargeItems", List.of(Map.of(
                "itemId", merchantRefNum,
                "description", "Order " + merchantRefNum,
                "price", new BigDecimal(amount),
                "quantity", 1)));
        body.put("signature", chargeSignature(merchantRefNum, customerProfileId, amount));

        try {
            Map<String, Object> response = post("/ECommerceWeb/Fawry/payments/charge", body);

            String statusCode = String.valueOf(response.getOrDefault("statusCode", ""));
            if (!"200".equals(statusCode)) {
                String description = String.valueOf(response.getOrDefault("statusDescription", "unknown"));
                log.error("fawry_charge_rejected order={} status={} detail={}",
                        merchantRefNum, statusCode, description);
                throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR,
                        "Fawry rejected the charge: " + description);
            }

            String referenceNumber = String.valueOf(response.get("referenceNumber"));
            Instant expiresAt = Instant.now().plusSeconds(fawry.getExpiryHours() * 3600L);

            log.info("fawry_payment_initiated order={} reference={} expires={}",
                    merchantRefNum, referenceNumber, expiresAt);

            return new Initiation(
                    referenceNumber,
                    merchantRefNum,
                    null,
                    null,
                    referenceNumber,
                    expiresAt,
                    false,
                    PaymentPayloadRedactor.redact(response));

        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("fawry_initiate_failed order={} error={}", merchantRefNum, e.toString());
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR, "Fawry is unreachable", e);
        }
    }

    /** {@code SHA-256(merchantCode + merchantRefNum + customerProfileId + paymentMethod + amount + secureKey)} */
    private String chargeSignature(String merchantRefNum, String customerProfileId, String amount) {
        AppProperties.Payment.Fawry fawry = config();
        return Hashes.sha256Hex(fawry.getMerchantCode()
                + merchantRefNum
                + customerProfileId
                + PAY_AT_FAWRY
                + amount
                + fawry.getSecurityKey());
    }

    // --------------------------------------------------------------- verify --

    @Override
    public Verification verify(String merchantRefNumber) {
        AppProperties.Payment.Fawry fawry = config();
        String signature = Hashes.sha256Hex(
                fawry.getMerchantCode() + merchantRefNumber + fawry.getSecurityKey());

        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/ECommerceWeb/Fawry/payments/status/v2")
                            .queryParam("merchantCode", fawry.getMerchantCode())
                            .queryParam("merchantRefNumber", merchantRefNumber)
                            .queryParam("signature", signature)
                            .build())
                    .retrieve()
                    .body(Map.class);

            Map<String, Object> payload = response == null ? Map.of() : response;
            Status status = toStatus(String.valueOf(payload.getOrDefault("orderStatus", "")));

            return new Verification(
                    status,
                    String.valueOf(payload.getOrDefault("fawryRefNumber", merchantRefNumber)),
                    amountOf(payload, "paymentAmount"),
                    null,
                    null,
                    status == Status.FAILED ? String.valueOf(payload.getOrDefault("statusCode", "")) : null,
                    status == Status.FAILED ? String.valueOf(payload.getOrDefault("statusDescription", "")) : null,
                    PaymentPayloadRedactor.redact(payload));

        } catch (RuntimeException e) {
            log.warn("fawry_verify_failed reference={} error={}", merchantRefNumber, e.toString());
            return new Verification(Status.UNKNOWN, merchantRefNumber, null, null, null,
                    "VERIFY_FAILED", e.getMessage(), Map.of());
        }
    }

    // ------------------------------------------------------------- callback --

    @Override
    public Callback parseCallback(String rawBody, Map<String, String> headers) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            log.warn("fawry_callback_unparseable error={}", e.getMessage());
            return Callback.invalid(null, Map.of());
        }

        Map<String, Object> redacted = PaymentPayloadRedactor.redact(payload);

        String fawryRefNumber = string(payload, "fawryRefNumber");
        String merchantRefNumber = string(payload, "merchantRefNumber");
        String orderStatus = string(payload, "orderStatus");

        // Fawry sends no event id, so one is derived. Combining the reference with the status means
        // a later "PAID" after an earlier "NEW" is a distinct event, while a genuine duplicate of
        // either collides on the unique constraint and is skipped.
        String eventId = fawryRefNumber + ":" + orderStatus;

        String received = string(payload, "messageSignature");
        String expected = callbackSignature(payload);

        if (!Hashes.constantTimeEquals(received, expected)) {
            log.warn("fawry_callback_signature_invalid merchantRef={} status={}",
                    merchantRefNumber, orderStatus);
            return Callback.invalid(eventId, redacted);
        }

        Status status = toStatus(orderStatus);

        return new Callback(
                true,
                eventId,
                orderStatus,
                // Matched on our own order number, which was sent as merchantRefNum.
                merchantRefNumber,
                status,
                amountOf(payload, "paymentAmount"),
                null,
                null,
                status == Status.FAILED ? string(payload, "statusCode") : null,
                status == Status.FAILED ? string(payload, "statusDescription") : null,
                redacted);
    }

    /**
     * {@code SHA-256(fawryRefNumber + merchantRefNumber + paymentAmount + orderAmount + orderStatus
     * + paymentMethod + paymentRefrenceNumber + secureKey)}
     *
     * <p>The misspelling of {@code paymentRefrenceNumber} is Fawry's, not a typo here - correcting
     * it produces a digest that never matches.
     */
    private String callbackSignature(Map<String, Object> payload) {
        return Hashes.sha256Hex(
                string(payload, "fawryRefNumber")
                        + string(payload, "merchantRefNumber")
                        + formatAmount(decimal(payload, "paymentAmount"))
                        + formatAmount(decimal(payload, "orderAmount"))
                        + string(payload, "orderStatus")
                        + string(payload, "paymentMethod")
                        + string(payload, "paymentRefrenceNumber")
                        + config().getSecurityKey());
    }

    // --------------------------------------------------------------- refund --

    @Override
    public RefundResult refund(String referenceNumber, Money amount, String reason) {
        AppProperties.Payment.Fawry fawry = config();
        String formatted = formatAmount(amount.amount());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantCode", fawry.getMerchantCode());
        body.put("referenceNumber", referenceNumber);
        body.put("refundAmount", new BigDecimal(formatted));
        body.put("reason", reason);
        body.put("signature", Hashes.sha256Hex(
                fawry.getMerchantCode() + referenceNumber + formatted
                        + (reason == null ? "" : reason) + fawry.getSecurityKey()));

        try {
            Map<String, Object> response = post("/ECommerceWeb/Fawry/payments/refund", body);
            boolean succeeded = "200".equals(String.valueOf(response.getOrDefault("statusCode", "")));

            return new RefundResult(succeeded,
                    succeeded ? referenceNumber : null,
                    succeeded ? null : String.valueOf(response.getOrDefault("statusDescription", "")),
                    PaymentPayloadRedactor.redact(response));

        } catch (RuntimeException e) {
            log.error("fawry_refund_failed reference={} error={}", referenceNumber, e.toString());
            return new RefundResult(false, null, e.getMessage(), Map.of());
        }
    }

    // -------------------------------------------------------------- plumbing --

    /**
     * Maps Fawry's order status onto the provider-neutral vocabulary.
     *
     * <p>{@code NEW} and {@code UNPAID} both mean "reference issued, nobody has paid yet" and must
     * not be mistaken for a failure - the customer may simply not have reached an outlet.
     */
    private static Status toStatus(String orderStatus) {
        return switch (orderStatus == null ? "" : orderStatus.toUpperCase()) {
            case "PAID" -> Status.CAPTURED;
            case "NEW", "UNPAID" -> Status.PENDING;
            case "CANCELED", "CANCELLED" -> Status.CANCELLED;
            case "EXPIRED" -> Status.EXPIRED;
            case "REFUNDED", "PARTIAL_REFUNDED" -> Status.REFUNDED;
            case "FAILED" -> Status.FAILED;
            default -> Status.UNKNOWN;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        Map<String, Object> response = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new IllegalStateException("Fawry returned an empty body for " + path);
        }
        return response;
    }

    /** Exactly two decimal places - the format Fawry both expects and signs over. */
    private static String formatAmount(BigDecimal amount) {
        return amount == null
                ? "0.00"
                : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static Money amountOf(Map<String, Object> payload, String key) {
        BigDecimal amount = decimal(payload, key);
        return amount == null ? null : Money.of(amount, Money.DEFAULT_CURRENCY);
    }

    private static BigDecimal decimal(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private static String string(Map<String, Object> payload, String key) {
        return Optional.ofNullable(payload.get(key)).map(String::valueOf).orElse("");
    }

    private AppProperties.Payment.Fawry config() {
        return properties.getPayment().getFawry();
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Fawry is not configured (FAWRY_MERCHANT_CODE / FAWRY_SECURITY_KEY)");
        }
    }
}
