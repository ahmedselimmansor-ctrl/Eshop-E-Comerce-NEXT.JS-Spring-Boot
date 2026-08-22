package com.eshop.admin;

import com.eshop.support.Auth;
import com.eshop.support.IntegrationTest;
import com.eshop.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard summary, on a store that has not sold anything yet.
 *
 * <p>This is the shape every new store sees in its first week, and it is the case the console got
 * wrong: the API returns a null {@code changePercent} when the preceding period was zero — growth
 * from nothing is not a percentage — and the web client's schema demanded a number, which rejected
 * the whole payload and blanked the dashboard.
 *
 * <p>The contract asserted here is the one the client now models: the metric object is always
 * present, and {@code changePercent} may be absent.
 */
class DashboardApiTest extends IntegrationTest {

    @Test
    @DisplayName("an empty store returns a whole summary rather than failing")
    void emptyStoreStillReturnsEveryMetric() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");

        mvc.perform(get("/api/v1/admin/dashboard/summary")
                        .param("rangeDays", "30")
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenue.current.amount").exists())
                .andExpect(jsonPath("$.revenue.current.currency").value("EGP"))
                .andExpect(jsonPath("$.orders.current").value(0))
                .andExpect(jsonPath("$.averageOrderValue").exists())
                .andExpect(jsonPath("$.newCustomers").exists())
                // The operational queues the console renders as counters.
                .andExpect(jsonPath("$.awaitingPayment").exists())
                .andExpect(jsonPath("$.awaitingFulfilment").exists())
                .andExpect(jsonPath("$.pendingReviews").exists())
                .andExpect(jsonPath("$.outOfStockSkus").exists());
    }

    @Test
    @DisplayName("growth from a zero baseline reports no percentage rather than a fabricated one")
    void changePercentIsAbsentWhenThereIsNoBaseline() throws Exception {
        // One customer registered in this period, none in the one before it.
        User admin = fixtures.superAdmin("admin@test.local");

        mvc.perform(get("/api/v1/admin/dashboard/summary")
                        .param("rangeDays", "30")
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newCustomers.current").value(1))
                .andExpect(jsonPath("$.newCustomers.previous").value(0))
                // Deliberately not 100%. The client renders no change indicator at all.
                .andExpect(jsonPath("$.newCustomers.changePercent").doesNotExist());
    }

    @Test
    @DisplayName("the supporting panels return arrays even with nothing to show")
    void panelsReturnEmptyArrays() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");

        for (String path : new String[]{
                "/api/v1/admin/dashboard/revenue-series",
                "/api/v1/admin/dashboard/top-products",
                "/api/v1/admin/dashboard/low-stock",
                "/api/v1/admin/dashboard/zero-result-searches"}) {

            mvc.perform(get(path).with(Auth.as(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }

    @Test
    @DisplayName("a customer cannot read the dashboard")
    void customerIsForbidden() throws Exception {
        User customer = fixtures.customer("shopper@test.local");

        mvc.perform(get("/api/v1/admin/dashboard/summary").with(Auth.as(customer)))
                .andExpect(status().isForbidden());
    }
}
