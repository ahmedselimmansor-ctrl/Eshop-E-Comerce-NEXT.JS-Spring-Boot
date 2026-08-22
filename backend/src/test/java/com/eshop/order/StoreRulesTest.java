package com.eshop.order;

import com.eshop.catalog.domain.Product;
import com.eshop.settings.service.SettingsService;
import com.eshop.support.Auth;
import com.eshop.support.IntegrationTest;
import com.eshop.user.domain.Address;
import com.eshop.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The store rules that live in the settings table, exercised through checkout.
 *
 * <p>This is a regression test for the test harness as much as for the application.
 * {@code TRUNCATE ... CASCADE} empties every table with a foreign key into the named ones, deciding
 * that from the constraints rather than the rows, so {@code settings} went with {@code users} on
 * every reset. The consequence was invisible and total: the minimum-order check compared against its
 * zero fallback and the cash-on-delivery surcharge was silently nothing, so neither rule was
 * actually under test in any of the checkout tests that appeared to cover them.
 *
 * <p>Asserting on the seeded values rather than on values written by the test is deliberate. If the
 * reference data goes missing again, these fail.
 */
class StoreRulesTest extends IntegrationTest {

    @Autowired SettingsService settings;

    @Test
    @DisplayName("the seeded reference settings survive a fixture reset")
    void referenceSettingsArePresent() {
        assertThat(settings.decimal(SettingsService.CHECKOUT_MIN_ORDER, BigDecimal.valueOf(-1)))
                .as("checkout.min_order comes from R__seed_reference_data.sql")
                .isEqualByComparingTo("50.00");

        assertThat(settings.decimal(SettingsService.CHECKOUT_COD_FEE, BigDecimal.valueOf(-1)))
                .isEqualByComparingTo("20.00");

        assertThat(settings.bool(SettingsService.CHECKOUT_GUEST_ENABLED, false)).isTrue();
    }

    @Test
    @DisplayName("an order below the store minimum is refused")
    void belowMinimumIsRefused() throws Exception {
        User shopper = fixtures.customer("shopper@test.local");
        // 10.00, comfortably under the seeded 50.00 minimum.
        Product cheap = fixtures.product("SKU-CHEAP", "رخيص", "Cheap",
                new BigDecimal("10.00"), com.eshop.catalog.domain.ProductStatus.ACTIVE);
        fixtures.stock(cheap, 50);
        Address address = fixtures.address(shopper);

        mvc.perform(post("/api/v1/cart/items")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("productId", cheap.getId(), "quantity", 1)))
                        .with(Auth.as(shopper)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/checkout/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "shippingAddressId", address.getId(),
                                "shippingMethodId", fixtures.shippingMethod().getId(),
                                "paymentProvider", "COD",
                                "paymentMethod", "COD")))
                        .with(Auth.as(shopper)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("BELOW_MINIMUM"));
    }

    @Test
    @DisplayName("the cash-on-delivery surcharge is added to the total")
    void codFeeIsCharged() throws Exception {
        User shopper = fixtures.customer("shopper@test.local");
        Product product = fixtures.sellableProduct("SKU-1", 20);   // 100.00 each
        Address address = fixtures.address(shopper);

        mvc.perform(post("/api/v1/cart/items")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("productId", product.getId(), "quantity", 2)))
                        .with(Auth.as(shopper)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/checkout/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "shippingAddressId", address.getId(),
                                "shippingMethodId", fixtures.shippingMethod().getId(),
                                "paymentProvider", "COD",
                                "paymentMethod", "COD")))
                        .with(Auth.as(shopper)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.totals.subtotal.amount").value("200.00"))
                // The seeded surcharge, not zero. This is the assertion the cascade defect hid.
                .andExpect(jsonPath("$.order.totals.codFee.amount").value("20.00"));
    }
}
