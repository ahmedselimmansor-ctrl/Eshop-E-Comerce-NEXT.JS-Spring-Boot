package com.eshop.order;

import com.eshop.catalog.domain.Product;
import com.eshop.support.Auth;
import com.eshop.support.IntegrationTest;
import com.eshop.user.domain.Address;
import com.eshop.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Placing an order, and placing it twice.
 *
 * <p>The retry case is the one that matters. A mobile client that loses its connection after the
 * request was received has no way to know whether the order exists, so it retries — and without a
 * working idempotency record that second attempt is a second order and a second charge.
 *
 * <p>This is also a regression test. {@code IdempotencyKey.responseBody} was declared as a bare
 * {@code Object} with {@code @JdbcTypeCode(SqlTypes.JSON)}; Hibernate could not infer a serialiser
 * and fell back to {@code String}, so storing the response threw {@code ClassCastException} at
 * commit. Checkout failed outright — every order, not merely the retried ones.
 */
class CheckoutIdempotencyTest extends IntegrationTest {

    @Test
    @DisplayName("an order is placed from the cart")
    void placesAnOrder() throws Exception {
        Context context = context();

        mvc.perform(post("/api/v1/checkout/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(checkoutBody(context))
                        .with(Auth.as(context.shopper())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.orderNumber").exists())
                .andExpect(jsonPath("$.order.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.order.items.length()").value(1));
    }

    @Test
    @DisplayName("retrying with the same key returns the first order rather than creating a second")
    void retryReplaysTheSameOrder() throws Exception {
        Context context = context();
        String key = UUID.randomUUID().toString();

        String first = mvc.perform(post("/api/v1/checkout/orders")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(checkoutBody(context))
                        .with(Auth.as(context.shopper())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String replayed = mvc.perform(post("/api/v1/checkout/orders")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(checkoutBody(context))
                        .with(Auth.as(context.shopper())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(replayed).at("/order/orderNumber").asText())
                .as("the retry must replay the original order, not place another")
                .isEqualTo(json.readTree(first).at("/order/orderNumber").asText());
    }

    @Test
    @DisplayName("checkout without an Idempotency-Key is a 400, not a 500")
    void missingIdempotencyKeyIsARequestError() throws Exception {
        Context context = context();

        // Spring rejects this before the handler runs, so it is only a 400 because
        // MissingRequestHeaderException is mapped. Unmapped, it fell through to the catch-all and a
        // client that had merely forgotten a header was told the server had broken.
        mvc.perform(post("/api/v1/checkout/orders")
                        .contentType("application/json")
                        .content(checkoutBody(context))
                        .with(Auth.as(context.shopper())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    // ------------------------------------------------------------------------

    private record Context(User shopper, Address address, Product product, UUID shippingMethodId) {
    }

    private Context context() throws Exception {
        User shopper = fixtures.customer("shopper@test.local");
        Product product = fixtures.sellableProduct("SKU-1", 20);
        Address address = fixtures.address(shopper);

        mvc.perform(post("/api/v1/cart/items")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "productId", product.getId(), "quantity", 2)))
                        .with(Auth.as(shopper)))
                .andExpect(status().isOk());

        return new Context(shopper, address, product, fixtures.shippingMethod().getId());
    }

    private String checkoutBody(Context context) throws Exception {
        return json.writeValueAsString(Map.of(
                "shippingAddressId", context.address().getId(),
                "shippingMethodId", context.shippingMethodId(),
                "paymentProvider", "COD",
                "paymentMethod", "COD"));
    }
}
