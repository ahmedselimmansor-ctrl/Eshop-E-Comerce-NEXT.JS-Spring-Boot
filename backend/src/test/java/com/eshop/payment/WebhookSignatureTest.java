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
 * Webhook signature verification, with a secret configured.
 *
 * <p>This endpoint is unauthenticated by necessity - the provider calls it, not the customer - so the
 * HMAC is the only thing standing between a stranger and an order marked paid. These tests drive it
 * through the real HTTP stack with a known secret and check both directions: a correctly signed
 * callback is accepted, and every tampered variant of it is not.
 */
@TestPropertySource(properties = {
        "eshop.payment.paymob.enabled=true",
        "eshop.payment.paymob.api-key=test-api-key",
        "eshop.payment.paymob.hmac-secret=" + WebhookSignatureTest.SECRET,
})
class WebhookSignatureTest extends IntegrationTest {

    static final String SECRET = "test-hmac-secret-not-a-real-one";

    @Autowired JdbcTemplate jdbc;

    private static final List<String> HMAC_FIELDS = List.of(
            "amount_cents", "created_at", "currency", "error_occured", "has_parent_transaction",
            "id", "integration_id", "is_3d_secure", "is_auth", "is_capture",
            "is_refunded", "is_standalone_payment", "is_voided", "order.id", "owner",
            "pending", "source_data.pan", "source_data.sub_type", "source_data.type", "success");

    @Test
    @DisplayName("a correctly signed callback is accepted")
    void validSignatureIsAccepted() throws Exception {
        post(transaction(), sign(transaction()));

        assertThat(validCount())
                .as("a callback signed with the configured secret must verify")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a callback signed with the wrong secret is rejected")
    void wrongSecretIsRejected() throws Exception {
        String wrong = Hashes.hmacSha512Hex(concatenate(transaction()), "a-different-secret");
        post(transaction(), wrong);

        assertThat(validCount()).isZero();
    }

    @Test
    @DisplayName("changing the amount after signing is rejected")
    void tamperedAmountIsRejected() throws Exception {
        // The attack that matters: take a genuine 1000 EGP callback and claim it was 10.
        Map<String, Object> genuine = transaction();
        String signature = sign(genuine);

        Map<String, Object> tampered = transaction();
        tampered.put("amount_cents", 1000);

        post(tampered, signature);

        assertThat(validCount())
                .as("the amount is inside the signed field set, so altering it must invalidate it")
                .isZero();
    }

    @Test
    @DisplayName("flipping success after signing is rejected")
    void tamperedSuccessIsRejected() throws Exception {
        Map<String, Object> genuine = transaction();
        genuine.put("success", false);
        String signature = sign(genuine);

        Map<String, Object> tampered = transaction();
        tampered.put("success", true);

        post(tampered, signature);

        assertThat(validCount())
                .as("a failed payment must not be replayable as a successful one")
                .isZero();
    }

    @Test
    @DisplayName("a missing signature is rejected")
    void missingSignatureIsRejected() throws Exception {
        post(transaction(), "");
        assertThat(validCount()).isZero();
    }

    @Test
    @DisplayName("an unparseable body is recorded rather than throwing")
    void unparseableBodyIsRecorded() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/payments/webhooks/paymob")
                        .contentType("application/json")
                        .content("{not json"))
                .andExpect(status().isOk());

        assertThat(validCount()).isZero();
    }

    @Test
    @DisplayName("the same event delivered twice is processed once")
    void duplicateDeliveryIsIgnored() throws Exception {
        String signature = sign(transaction());

        post(transaction(), signature);
        post(transaction(), signature);

        // Providers retry aggressively; the unique (provider, event_id) constraint is what stops a
        // capture being applied twice.
        Integer stored = jdbc.queryForObject(
                "SELECT count(*) FROM payment_webhook_events", Integer.class);

        assertThat(stored).as("the retry is recognised, not stored again").isEqualTo(1);
    }

    // ------------------------------------------------------------------------

    private void post(Map<String, Object> transaction, String hmac) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "TRANSACTION");
        payload.put("obj", transaction);
        payload.put("hmac", hmac);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/payments/webhooks/paymob")
                        .contentType("application/json")
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    private Integer validCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_webhook_events WHERE signature_valid = TRUE",
                Integer.class);
    }

    private String sign(Map<String, Object> transaction) {
        return Hashes.hmacSha512Hex(concatenate(transaction), SECRET);
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
