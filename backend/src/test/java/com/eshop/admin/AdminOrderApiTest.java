package com.eshop.admin;

import com.eshop.catalog.domain.Product;
import com.eshop.support.Auth;
import com.eshop.support.IntegrationTest;
import com.eshop.user.domain.Address;
import com.eshop.user.domain.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console's order screen and the actions on it.
 *
 * <p>{@code OrderResponse} walks items, their product references, the status timeline and the
 * shipment list. All four are lazy, so mapping the response in the controller — after the service
 * transaction had committed — threw {@code LazyInitializationException} on every call. These tests
 * exercise each action through the real HTTP stack, which is the only place that shows up.
 */
class AdminOrderApiTest extends IntegrationTest {

    @Test
    @DisplayName("detail returns the order with what is withheld from the customer")
    void detailIncludesInternalFields() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        String orderId = placeOrder();

        mvc.perform(get("/api/v1/admin/orders/" + orderId).with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderNumber").exists())
                .andExpect(jsonPath("$.order.items.length()").value(1))
                .andExpect(jsonPath("$.order.timeline").isArray())
                // Present on this endpoint and on no customer-facing one.
                .andExpect(jsonPath("$.payments").isArray())
                .andExpect(jsonPath("$.ip").exists());
    }

    @Test
    @DisplayName("status changes are recorded on the timeline")
    void statusChangeAppendsToTimeline() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        String orderId = placeOrder();

        mvc.perform(post("/api/v1/admin/orders/" + orderId + "/status")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "status", "PAID", "note", "COD confirmed")))
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mvc.perform(get("/api/v1/admin/orders/" + orderId).with(Auth.as(admin)))
                .andExpect(jsonPath("$.order.timeline.length()").value(2));
    }

    @Test
    @DisplayName("a partial shipment records what was sent and leaves the rest outstanding")
    void partialFulfilment() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        String orderId = placeOrder();

        String detail = mvc.perform(get("/api/v1/admin/orders/" + orderId).with(Auth.as(admin)))
                .andReturn().getResponse().getContentAsString();
        JsonNode item = json.readTree(detail).at("/order/items/0");
        String itemId = item.get("id").asText();

        advanceTo(admin, orderId, "PAID");
        advanceTo(admin, orderId, "PROCESSING");

        mvc.perform(post("/api/v1/admin/orders/" + orderId + "/fulfil")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "quantitiesByItemId", Map.of(itemId, 1),
                                "carrier", "Bosta",
                                "trackingNumber", "BST-123")))
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].fulfilledQuantity").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    @DisplayName("an internal note never reaches the customer's view of the order")
    void adminNoteIsInternal() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        String orderId = placeOrder();

        mvc.perform(post("/api/v1/admin/orders/" + orderId + "/notes")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("note", "Customer called to confirm.")))
                        .with(Auth.as(admin)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/admin/orders/" + orderId).with(Auth.as(admin)))
                .andExpect(jsonPath("$.adminNote").value("Customer called to confirm."))
                // The nested order is the customer-facing shape and must not carry it.
                .andExpect(jsonPath("$.order.adminNote").doesNotExist());
    }

    @Test
    @DisplayName("refunding an order with no captured payment is refused")
    void refundWithoutCapturedPaymentIsRefused() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        String orderId = placeOrder();

        // Cash on delivery: nothing has been taken yet, so there is nothing to give back. A gateway
        // will not cleanly undo a refund that should never have been issued.
        mvc.perform(post("/api/v1/admin/orders/" + orderId + "/refund")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "amount", "100.00", "reason", "Goodwill")))
                        .with(Auth.as(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a refund without an Idempotency-Key is a 400 naming the header")
    void refundRequiresIdempotencyKey() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        String orderId = placeOrder();

        mvc.perform(post("/api/v1/admin/orders/" + orderId + "/refund")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "amount", "100.00", "reason", "Goodwill")))
                        .with(Auth.as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    // ------------------------------------------------------------------------

    private void advanceTo(User admin, String orderId, String status) throws Exception {
        mvc.perform(post("/api/v1/admin/orders/" + orderId + "/status")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("status", status)))
                        .with(Auth.as(admin)))
                .andExpect(status().isOk());
    }

    /** Places a real order through checkout, so the fixture is one the application produced. */
    private String placeOrder() throws Exception {
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

        return json.readTree(placed).at("/order/id").asText();
    }
}
