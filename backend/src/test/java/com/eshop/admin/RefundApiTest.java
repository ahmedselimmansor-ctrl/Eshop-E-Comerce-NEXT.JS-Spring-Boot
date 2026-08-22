package com.eshop.admin;

import com.eshop.catalog.domain.Product;
import com.eshop.support.Auth;
import com.eshop.support.IntegrationTest;
import com.eshop.user.domain.Address;
import com.eshop.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refunds, against an order whose payment has actually been captured.
 *
 * <p>This path went unexercised for a long time for an understandable reason: every test order was
 * cash on delivery, nothing had been collected, and the endpoint correctly refused with 409. That is
 * right behaviour, but refusing is not the same as refunding, and the interesting cases - refunding
 * more than was paid, and a retried refund paying out twice - were all beyond it.
 *
 * <p>Capturing the payment first, the way collection does, makes the rest reachable. A cash-on-
 * delivery refund is recorded rather than sent to a gateway, so no external service is involved.
 */
class RefundApiTest extends IntegrationTest {

    @Test
    @DisplayName("a captured order can be refunded in full")
    void fullRefund() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Captured order = placeAndCapture();

        mvc.perform(refund(admin, order.id(), money(order.total()), UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount.amount").value(money(order.total())))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("a partial refund leaves the rest refundable")
    void partialThenRemainder() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Captured order = placeAndCapture();

        BigDecimal half = order.total().divide(BigDecimal.valueOf(2), 2, RoundingMode.DOWN);
        BigDecimal rest = order.total().subtract(half);

        mvc.perform(refund(admin, order.id(), money(half), UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        // The balance, not the original total.
        mvc.perform(refund(admin, order.id(), money(rest), UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("refunding more than was paid is refused")
    void overRefundIsRefused() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Captured order = placeAndCapture();

        // A gateway will not cleanly undo an over-refund, and by the time the mistake is visible the
        // money has already left.
        BigDecimal tooMuch = order.total().add(BigDecimal.ONE);
        mvc.perform(refund(admin, order.id(), money(tooMuch), UUID.randomUUID().toString()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("refunding most of it twice is refused")
    void cumulativeOverRefundIsRefused() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Captured order = placeAndCapture();

        // Nine tenths, twice, would pay out almost double.
        BigDecimal most = order.total()
                .multiply(BigDecimal.valueOf(0.9))
                .setScale(2, RoundingMode.DOWN);

        mvc.perform(refund(admin, order.id(), money(most), UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        mvc.perform(refund(admin, order.id(), money(most), UUID.randomUUID().toString()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a retried refund replays the first rather than paying out twice")
    void retryReplaysTheSameRefund() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Captured order = placeAndCapture();
        String key = UUID.randomUUID().toString();

        // Two thirds, so that a second genuine refund of the same size would exceed the balance and
        // a replay would not.
        BigDecimal chunk = order.total()
                .multiply(BigDecimal.valueOf(0.67))
                .setScale(2, RoundingMode.DOWN);

        String first = mvc.perform(refund(admin, order.id(), money(chunk), key))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String replayed = mvc.perform(refund(admin, order.id(), money(chunk), key))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(replayed).get("id").asText())
                .as("the same key must return the original refund, not create a second")
                .isEqualTo(json.readTree(first).get("id").asText());

        // The balance must reflect one refund, not two: another chunk of the same size would take
        // the total past what was captured.
        mvc.perform(refund(admin, order.id(), money(chunk), UUID.randomUUID().toString()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a caller without the refund permission is refused")
    void requiresRefundPermission() throws Exception {
        User customer = fixtures.customer("shopper2@test.local");
        Captured order = placeAndCapture();

        mvc.perform(refund(customer, order.id(), "10.00", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------

    private org.springframework.test.web.servlet.RequestBuilder refund(
            User actor, String orderId, String amount, String key) throws Exception {

        return post("/api/v1/admin/orders/" + orderId + "/refund")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("amount", amount, "reason", "Goodwill")))
                .with(Auth.as(actor));
    }

    /** An order whose payment has been captured, with the amount that was actually taken. */
    private record Captured(String id, BigDecimal total) {
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * Places a real order and marks its payment captured.
     *
     * <p>The captured amount is read back from the order rather than assumed: it is the subtotal
     * plus tax, shipping and the cash-on-delivery fee, and every one of those comes from seeded
     * settings that a later migration could reasonably change.
     */
    private Captured placeAndCapture() throws Exception {
        User shopper = fixtures.customer("shopper@test.local");
        Product product = fixtures.sellableProduct("SKU-1", 20);
        Address address = fixtures.address(shopper);

        mvc.perform(post("/api/v1/cart/items")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "productId", product.getId(), "quantity", 2)))
                        .with(Auth.as(shopper)))
                .andExpect(status().isOk());

        String placed = mvc.perform(post("/api/v1/checkout/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "shippingAddressId", address.getId(),
                                "shippingMethodId", fixtures.shippingMethod().getId(),
                                "paymentProvider", "COD",
                                "paymentMethod", "COD")))
                        .with(Auth.as(shopper)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String orderId = json.readTree(placed).at("/order/id").asText();
        BigDecimal total = new BigDecimal(
                json.readTree(placed).at("/order/totals/grandTotal/amount").asText());

        fixtures.capturePayment(UUID.fromString(orderId));
        return new Captured(orderId, total);
    }
}
