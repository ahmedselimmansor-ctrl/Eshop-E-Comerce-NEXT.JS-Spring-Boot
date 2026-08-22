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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Paymob Accept integration — cards, mobile wallets, and bank instalments.
 *
 * <p>Paymob's classic flow is three calls, in order:
 * <ol>
 *   <li><b>Authenticate</b> — exchange the API key for a short-lived token.</li>
 *   <li><b>Register order</b> — create Paymob's own order, quoting ours as
 *       {@code merchant_order_id} so callbacks can be matched back.</li>
 *   <li><b>Payment key</b> — mint a token scoped to one integration (card / wallet / instalment)
 *       and load it into a hosted iframe.</li>
 * </ol>
 *
 * <p>Amounts travel in <b>piastres</b>, not pounds. Sending 1299 where 129900 was meant undercharges
 * by a factor of a hundred, so every amount goes through {@link Money#toMinorUnits()} rather than
 * being formatted by hand.
 *
 * <p>Callbacks are authenticated with an HMAC-SHA512 over a fixed, alphabetically ordered
 * concatenation of specific fields. The field list and its order are dictated by Paymob — changing
 * either silently breaks verification, so {@link #HMAC_FIELDS} is written out explicitly.
 */
@Slf4j
@Component
public class PaymobPaymentProvider implements PaymentProvider {

    /**
     * Exactly the fields Paymob concatenates for the callback HMAC, in the order it uses.
     * Do not reorder or "tidy" this list.
     */
    private static final List<String> HMAC_FIELDS = List.of(
            "amount_cents", "created_at", "currency", "error_occured", "has_parent_transaction",
            "id", "integration_id", "is_3d_secure", "is_auth", "is_capture",
            "is_refunded", "is_standalone_payment", "is_voided", "order.id", "owner",
            "pending", "source_data.pan", "source_data.sub_type", "source_data.type", "success");

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public PaymobPaymentProvider(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getPayment().getPaymob().getBaseUrl())
                .build();
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.PAYMOB;
    }

    @Override
    public boolean isConfigured() {
        return config().isConfigured();
    }

    @Override
    public boolean supports(PaymentMethod method) {
        return method == PaymentMethod.CARD
                || method == PaymentMethod.WALLET
                || method == PaymentMethod.INSTALLMENT;
    }

    // ------------------------------------------------------------- initiate --

    @Override
    public Initiation initiate(PaymentRequest request) {
        AppProperties.Payment.Paymob paymob = config();
        requireConfigured();

        try {
            String authToken = authenticate();
            Map<String, Object> paymobOrder = registerOrder(authToken, request);
            String paymobOrderId = String.valueOf(paymobOrder.get("id"));

            String paymentKey = requestPaymentKey(authToken, paymobOrderId, request);

            String iframeUrl = paymob.getBaseUrl() + "/acceptance/iframes/"
                    + paymob.getIframeId() + "?payment_token=" + paymentKey;

            log.info("paymob_payment_initiated order={} paymob_order={} method={}",
                    request.orderNumber(), paymobOrderId, request.method());

            return new Initiation(
                    null,                       // the transaction id only exists after the customer pays
                    paymobOrderId,
                    iframeUrl,
                    iframeUrl,
                    null,
                    // Paymob payment keys are valid for one hour.
                    Instant.now().plusSeconds(3600),
                    false,
                    PaymentPayloadRedactor.redact(paymobOrder));

        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("paymob_initiate_failed order={} error={}", request.orderNumber(), e.toString());
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "Paymob rejected the payment request", e);
        }
    }

    private String authenticate() {
        Map<String, Object> response = post("/auth/tokens",
                Map.of("api_key", config().getApiKey()));

        return Optional.ofNullable(response.get("token"))
                .map(String::valueOf)
                .orElseThrow(() -> new IllegalStateException("Paymob returned no auth token"));
    }

    private Map<String, Object> registerOrder(String authToken, PaymentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auth_token", authToken);
        body.put("delivery_needed", false);
        body.put("amount_cents", request.amount().toMinorUnits());
        body.put("currency", request.amount().currency());
        // Our order number, echoed back on every callback — this is the join key.
        body.put("merchant_order_id", request.orderNumber());
        body.put("items", List.of());

        return post("/ecommerce/orders", body);
    }

    private String requestPaymentKey(String authToken, String paymobOrderId, PaymentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auth_token", authToken);
        body.put("amount_cents", request.amount().toMinorUnits());
        body.put("expiration", 3600);
        body.put("order_id", paymobOrderId);
        body.put("billing_data", billingData(request));
        body.put("currency", request.amount().currency());
        body.put("integration_id", integrationIdFor(request.method()));
        body.put("lock_order_when_paid", true);

        Map<String, Object> response = post("/acceptance/payment_keys", body);

        return Optional.ofNullable(response.get("token"))
                .map(String::valueOf)
                .orElseThrow(() -> new IllegalStateException("Paymob returned no payment key"));
    }

    /**
     * Paymob rejects the request if any billing field is missing, and it accepts the literal string
     * {@code "NA"} as a placeholder — which is what the optional fields fall back to.
     */
    private Map<String, Object> billingData(PaymentRequest request) {
        Map<String, Object> supplied = request.billingData() == null ? Map.of() : request.billingData();

        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("email", request.customerEmail());
        billing.put("phone_number", request.customerPhone());
        billing.put("first_name", firstName(request.customerName()));
        billing.put("last_name", lastName(request.customerName()));
        billing.put("street", value(supplied, "street"));
        billing.put("building", value(supplied, "building"));
        billing.put("floor", value(supplied, "floor"));
        billing.put("apartment", value(supplied, "apartment"));
        billing.put("city", value(supplied, "city"));
        billing.put("state", value(supplied, "governorate"));
        billing.put("country", "EG");
        billing.put("postal_code", value(supplied, "postalCode"));
        billing.put("shipping_method", "NA");
        return billing;
    }

    private String integrationIdFor(PaymentMethod method) {
        AppProperties.Payment.Paymob paymob = config();
        String integrationId = switch (method) {
            case CARD -> paymob.getIntegrationIdCard();
            case WALLET -> paymob.getIntegrationIdWallet();
            case INSTALLMENT -> paymob.getIntegrationIdInstallment();
            default -> "";
        };

        if (integrationId == null || integrationId.isBlank()) {
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "No Paymob integration id configured for " + method);
        }
        return integrationId;
    }

    // --------------------------------------------------------------- verify --

    @Override
    public Verification verify(String providerReference) {
        try {
            String authToken = authenticate();
            Map<String, Object> response = restClient.get()
                    .uri("/acceptance/transactions/{id}", providerReference)
                    .header("Authorization", "Bearer " + authToken)
                    .retrieve()
                    .body(Map.class);

            return toVerification(response == null ? Map.of() : response);

        } catch (RuntimeException e) {
            log.warn("paymob_verify_failed reference={} error={}", providerReference, e.toString());
            return new Verification(Status.UNKNOWN, providerReference, null, null, null,
                    "VERIFY_FAILED", e.getMessage(), Map.of());
        }
    }

    private Verification toVerification(Map<String, Object> transaction) {
        boolean success = Boolean.TRUE.equals(transaction.get("success"));
        boolean pending = Boolean.TRUE.equals(transaction.get("pending"));
        boolean refunded = Boolean.TRUE.equals(transaction.get("is_refunded"));
        boolean voided = Boolean.TRUE.equals(transaction.get("is_voided"));

        Status status = refunded ? Status.REFUNDED
                : voided ? Status.CANCELLED
                : success ? Status.CAPTURED
                : pending ? Status.PENDING
                : Status.FAILED;

        Map<String, Object> sourceData = asMap(transaction.get("source_data"));

        return new Verification(
                status,
                String.valueOf(transaction.get("id")),
                amountOf(transaction),
                String.valueOf(sourceData.getOrDefault("sub_type", "")),
                PaymentPayloadRedactor.lastFour(String.valueOf(sourceData.get("pan"))),
                success ? null : String.valueOf(transaction.getOrDefault("txn_response_code", "")),
                success ? null : String.valueOf(transaction.getOrDefault("data.message", "")),
                PaymentPayloadRedactor.redact(transaction));
    }

    // ------------------------------------------------------------- callback --

    @Override
    public Callback parseCallback(String rawBody, Map<String, String> headers) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            log.warn("paymob_callback_unparseable error={}", e.getMessage());
            return Callback.invalid(null, Map.of());
        }

        Map<String, Object> transaction = asMap(payload.get("obj"));
        String eventId = String.valueOf(transaction.getOrDefault("id", ""));
        Map<String, Object> redacted = PaymentPayloadRedactor.redact(payload);

        String receivedHmac = Optional.ofNullable(payload.get("hmac"))
                .map(String::valueOf)
                .orElse(headers.getOrDefault("hmac", ""));

        String expectedHmac = Hashes.hmacSha512Hex(
                concatenateHmacFields(transaction), config().getHmacSecret());

        if (!Hashes.constantTimeEquals(receivedHmac, expectedHmac)) {
            // Logged without either digest: printing the expected value would hand an attacker the
            // answer they are trying to guess.
            log.warn("paymob_callback_signature_invalid transaction={}", eventId);
            return Callback.invalid(eventId, redacted);
        }

        Verification verification = toVerification(transaction);
        Map<String, Object> sourceData = asMap(transaction.get("source_data"));

        return new Callback(
                true,
                eventId,
                String.valueOf(payload.getOrDefault("type", "TRANSACTION")),
                // Matched on Paymob's order id, which we stored as providerOrderId at initiation.
                String.valueOf(asMap(transaction.get("order")).getOrDefault("id", "")),
                verification.status(),
                verification.amount(),
                String.valueOf(sourceData.getOrDefault("sub_type", "")),
                PaymentPayloadRedactor.lastFour(String.valueOf(sourceData.get("pan"))),
                verification.failureCode(),
                verification.failureReason(),
                redacted);
    }

    /**
     * Builds the exact string Paymob signs: the {@link #HMAC_FIELDS} values concatenated with no
     * separator, in that order, with booleans lower-cased and missing values empty.
     */
    private String concatenateHmacFields(Map<String, Object> transaction) {
        StringBuilder concatenated = new StringBuilder();
        for (String field : HMAC_FIELDS) {
            concatenated.append(resolveNested(transaction, field));
        }
        return concatenated.toString();
    }

    private String resolveNested(Map<String, Object> source, String path) {
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return "";
            }
            current = map.get(segment);
        }
        if (current == null) {
            return "";
        }
        return current instanceof Boolean flag ? String.valueOf(flag) : String.valueOf(current);
    }

    // --------------------------------------------------------------- refund --

    @Override
    public RefundResult refund(String providerPaymentId, Money amount, String reason) {
        try {
            String authToken = authenticate();
            Map<String, Object> response = post("/acceptance/void_refund/refund", Map.of(
                    "auth_token", authToken,
                    "transaction_id", providerPaymentId,
                    "amount_cents", amount.toMinorUnits()));

            boolean succeeded = Boolean.TRUE.equals(response.get("success"))
                    || response.containsKey("id");

            return new RefundResult(succeeded,
                    succeeded ? String.valueOf(response.get("id")) : null,
                    succeeded ? null : String.valueOf(response.getOrDefault("message", "Refund rejected")),
                    PaymentPayloadRedactor.redact(response));

        } catch (RuntimeException e) {
            log.error("paymob_refund_failed transaction={} error={}", providerPaymentId, e.toString());
            return new RefundResult(false, null, e.getMessage(), Map.of());
        }
    }

    // -------------------------------------------------------------- plumbing --

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        Map<String, Object> response = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new IllegalStateException("Paymob returned an empty body for " + path);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Money amountOf(Map<String, Object> transaction) {
        Object cents = transaction.get("amount_cents");
        if (cents == null) {
            return null;
        }
        String currency = String.valueOf(transaction.getOrDefault("currency", Money.DEFAULT_CURRENCY));
        return Money.fromMinorUnits(Long.parseLong(String.valueOf(cents)), currency);
    }

    private static String value(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null || String.valueOf(value).isBlank() ? "NA" : String.valueOf(value);
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "NA";
        }
        int space = fullName.trim().indexOf(' ');
        return space < 0 ? fullName.trim() : fullName.trim().substring(0, space);
    }

    private static String lastName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "NA";
        }
        int space = fullName.trim().indexOf(' ');
        return space < 0 ? "NA" : fullName.trim().substring(space + 1);
    }

    private AppProperties.Payment.Paymob config() {
        return properties.getPayment().getPaymob();
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Paymob is not configured (PAYMOB_API_KEY / PAYMOB_HMAC_SECRET)");
        }
    }
}
