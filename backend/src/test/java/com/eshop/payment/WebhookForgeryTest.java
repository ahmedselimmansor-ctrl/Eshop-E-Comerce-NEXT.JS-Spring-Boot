package com.eshop.payment;

import com.eshop.common.util.Hashes;
import com.eshop.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Can a stranger forge a "payment captured" webhook?
 *
 * <p>The webhook endpoint is unauthenticated by necessity: the provider calls it, not the customer.
 * Its only defence is the HMAC, so the question that matters is what happens when the shared secret
 * is not configured. That is the default state of a fresh deployment, and {@code .env.example} ships
 * {@code PAYMOB_HMAC_SECRET} empty.
 *
 * <p>An empty key is still a valid HMAC key. If the secret is blank and the code signs with it
 * anyway, anyone who knows the field order - which is public, and in the provider's own
 * documentation - can compute a matching digest and have an unpaid order marked as paid.
 */
@TestPropertySource(properties = {
        "eshop.payment.paymob.enabled=true",
        "eshop.payment.paymob.api-key=test-api-key",
        // Blank, exactly as a deployment that has not configured payments yet.
        "eshop.payment.paymob.hmac-secret=",
})
class WebhookForgeryTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;

    /** The fields Paymob concatenates for the callback HMAC, in its order. */
    private static final List<String> HMAC_FIELDS = List.of(
            "amount_cents", "created_at", "currency", "error_occured", "has_parent_transaction",
            "id", "integration_id", "is_3d_secure", "is_auth", "is_capture",
            "is_refunded", "is_standalone_payment", "is_voided", "order.id", "owner",
            "pending", "source_data.pan", "source_data.sub_type", "source_data.type", "success");

    @Test
    @DisplayName("a webhook forged with an empty-key HMAC is rejected when no secret is configured")
    void forgedCallbackIsRejectedWhenSecretIsMissing() throws Exception {
        Map<String, Object> transaction = transaction();

        // An attacker cannot compute an empty-key HMAC with Java's Mac, which rejects empty keys,
        // so they would send whatever they liked and rely on the server's own comparison.
        String forged = "0".repeat(128);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "TRANSACTION");
        payload.put("obj", transaction);
        payload.put("hmac", forged);

        mvc.perform(post("/api/v1/payments/webhooks/paymob")
                        .contentType("application/json")
                        .content(json.writeValueAsString(payload)))
                // 200, so the provider stops retrying a deployment that will never accept it. What
                // matters is whether it was acted on, not the status code.
                .andExpect(status().isOk());

        Integer accepted = jdbc.queryForObject("""
                SELECT count(*) FROM payment_webhook_events WHERE signature_valid = TRUE
                """, Integer.class);

        assertThat(accepted)
                .as("an unconfigured secret must not make a forged webhook valid")
                .isZero();

        Integer recorded = jdbc.queryForObject(
                "SELECT count(*) FROM payment_webhook_events", Integer.class);

        assertThat(recorded)
                .as("the attempt is still recorded, so it can be investigated")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a forged callback never captures a payment")
    void forgedCallbackCapturesNothing() throws Exception {
        Map<String, Object> transaction = transaction();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "TRANSACTION");
        payload.put("obj", transaction);
        payload.put("hmac", "0".repeat(128));

        mvc.perform(post("/api/v1/payments/webhooks/paymob")
                        .contentType("application/json")
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isOk());

        Integer captured = jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE status = 'CAPTURED'", Integer.class);

        assertThat(captured).as("nothing may be captured by an unverified callback").isZero();
    }

    private Map<String, Object> transaction() {
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("id", 987654321L);
        transaction.put("amount_cents", 100000);
        transaction.put("created_at", "2026-08-22T10:00:00Z");
        transaction.put("currency", "EGP");
        transaction.put("error_occured", false);
        transaction.put("has_parent_transaction", false);
        transaction.put("integration_id", 111222);
        transaction.put("is_3d_secure", true);
        transaction.put("is_auth", false);
        transaction.put("is_capture", false);
        transaction.put("is_refunded", false);
        transaction.put("is_standalone_payment", true);
        transaction.put("is_voided", false);
        transaction.put("order", Map.of("id", 555666777L));
        transaction.put("owner", 42);
        transaction.put("pending", false);
        transaction.put("source_data", Map.of("pan", "2346", "sub_type", "MasterCard", "type", "card"));
        // The whole point of the forgery.
        transaction.put("success", true);
        return transaction;
    }

    private String concatenate(Map<String, Object> transaction) {
        StringBuilder concatenated = new StringBuilder();
        for (String field : HMAC_FIELDS) {
            Object current = transaction;
            for (String segment : field.split("\\.")) {
                current = current instanceof Map<?, ?> map ? map.get(segment) : null;
            }
            concatenated.append(current == null ? "" : String.valueOf(current));
        }
        return concatenated.toString();
    }
}
