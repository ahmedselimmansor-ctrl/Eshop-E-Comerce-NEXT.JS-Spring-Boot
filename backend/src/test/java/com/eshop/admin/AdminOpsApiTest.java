package com.eshop.admin;

import com.eshop.catalog.domain.Product;
import com.eshop.inventory.domain.InventoryItem;
import com.eshop.review.domain.Review;
import com.eshop.support.Auth;
import com.eshop.support.IntegrationTest;
import com.eshop.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inventory and review moderation.
 *
 * <p>Both once mapped their response in the controller, from an entity whose session had already
 * closed. The stock row reads the product and warehouse; the review response reads its media
 * collection. Every call to adjust, receive, approve, reject and reply threw
 * {@code LazyInitializationException} — and the stock mutations had already been applied by then, so
 * the operator saw a 500 for an operation that had in fact succeeded.
 */
class AdminOpsApiTest extends IntegrationTest {

    // ------------------------------------------------------------- inventory --

    @Test
    @DisplayName("a stock take returns the new row rather than failing after applying")
    void adjustReturnsTheRow() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Product product = fixtures.sellableProduct("SKU-1", 10);
        InventoryItem item = fixtures.stockOf(product);

        mvc.perform(post("/api/v1/admin/inventory/" + item.getId() + "/adjust")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "quantityOnHand", 42, "reason", "Stock take")))
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(42))
                .andExpect(jsonPath("$.available").value(42))
                // Reads the lazily-associated product and warehouse — the exact fields that threw.
                .andExpect(jsonPath("$.productName").value("منتج"))
                .andExpect(jsonPath("$.warehouse").value("TST"));
    }

    @Test
    @DisplayName("receiving stock adds to what is on hand")
    void receiveAdds() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Product product = fixtures.sellableProduct("SKU-1", 10);
        InventoryItem item = fixtures.stockOf(product);

        mvc.perform(post("/api/v1/admin/inventory/" + item.getId() + "/receive")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("quantity", 8, "note", "PO-1")))
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(18));
    }

    @Test
    @DisplayName("stock cannot be set below what is already reserved")
    void adjustBelowReservedIsRefused() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Product product = fixtures.sellableProduct("SKU-1", 10);
        InventoryItem item = fixtures.reserve(fixtures.stockOf(product), 6);

        mvc.perform(post("/api/v1/admin/inventory/" + item.getId() + "/adjust")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "quantityOnHand", 3, "reason", "Bad count")))
                        .with(Auth.as(admin)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the list reports available as on-hand minus reserved")
    void listSeparatesReservedFromAvailable() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Product product = fixtures.sellableProduct("SKU-1", 10);
        fixtures.reserve(fixtures.stockOf(product), 4);

        mvc.perform(get("/api/v1/admin/inventory").with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].onHand").value(10))
                .andExpect(jsonPath("$.content[0].reserved").value(4))
                .andExpect(jsonPath("$.content[0].available").value(6));
    }

    // --------------------------------------------------------------- reviews --

    @Test
    @DisplayName("approving a review returns it instead of throwing on its media collection")
    void approveReturnsTheReview() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Review review = fixtures.review(fixtures.sellableProduct("SKU-1", 5),
                fixtures.customer("shopper@test.local"), 5);

        mvc.perform(post("/api/v1/admin/reviews/" + review.getId() + "/approve")
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("a store reply is saved on the review")
    void replyIsStored() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Review review = fixtures.review(fixtures.sellableProduct("SKU-1", 5),
                fixtures.customer("shopper@test.local"), 4);

        mvc.perform(post("/api/v1/admin/reviews/" + review.getId() + "/approve").with(Auth.as(admin)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/admin/reviews/" + review.getId() + "/reply")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("reply", "شكرًا لك")))
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminReply").value("شكرًا لك"));
    }

    @Test
    @DisplayName("rejecting a review takes it out of the pending queue")
    void rejectRemovesFromQueue() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Review review = fixtures.review(fixtures.sellableProduct("SKU-1", 5),
                fixtures.customer("shopper@test.local"), 1);

        mvc.perform(post("/api/v1/admin/reviews/" + review.getId() + "/reject")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("reason", "Spam")))
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mvc.perform(get("/api/v1/admin/reviews").param("status", "PENDING").with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // ------------------------------------------------------------- customers --

    @Test
    @DisplayName("a super admin cannot be suspended, so one account cannot lock out the rest")
    void superAdminCannotBeSuspended() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");

        mvc.perform(post("/api/v1/admin/customers/" + admin.getId() + "/status")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("status", "SUSPENDED")))
                        .with(Auth.as(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an ordinary customer can be suspended and reinstated")
    void customerCanBeSuspended() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        User shopper = fixtures.customer("shopper@test.local");

        mvc.perform(post("/api/v1/admin/customers/" + shopper.getId() + "/status")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("status", "SUSPENDED")))
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mvc.perform(post("/api/v1/admin/customers/" + shopper.getId() + "/status")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("status", "ACTIVE")))
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
