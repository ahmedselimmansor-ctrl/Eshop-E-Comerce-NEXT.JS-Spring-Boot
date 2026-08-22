package com.eshop.admin;

import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductStatus;
import com.eshop.support.Auth;
import com.eshop.support.IntegrationTest;
import com.eshop.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin catalog endpoints.
 *
 * <p>Two distinct regressions live here.
 *
 * <p>The list once mapped its rows in the controller, after the service transaction had committed.
 * With {@code open-in-view} disabled that reads a detached proxy, so every call threw
 * {@code LazyInitializationException} — while compiling perfectly well.
 *
 * <p>The editor once loaded a product through the storefront's locale-resolved DTO, which exposes a
 * single {@code name}. The form filled both language fields from it, so saving an untouched form
 * overwrote the Arabic name with the English one. Silent, and unrecoverable without a backup.
 */
class AdminProductApiTest extends IntegrationTest {

    @Test
    @DisplayName("list returns status and availability without touching a detached proxy")
    void listIncludesStatusAndStock() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        fixtures.sellableProduct("SKU-1", 25);
        fixtures.product("SKU-2", "مسودة", "Draft", new BigDecimal("50.00"), ProductStatus.DRAFT);

        mvc.perform(get("/api/v1/admin/products").param("size", "20").with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                // Drafts are visible here and nowhere on the storefront — the whole point of the screen.
                .andExpect(jsonPath("$.content[?(@.sku=='SKU-2')].status").value("DRAFT"))
                .andExpect(jsonPath("$.content[?(@.sku=='SKU-1')].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[?(@.sku=='SKU-1')].available").value(25))
                // A product with no stock row reports zero rather than being absent from the answer.
                .andExpect(jsonPath("$.content[?(@.sku=='SKU-2')].available").value(0));
    }

    @Test
    @DisplayName("status filter narrows to drafts")
    void filtersByStatus() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        fixtures.sellableProduct("SKU-1", 5);
        fixtures.product("SKU-2", "مسودة", "Draft", new BigDecimal("50.00"), ProductStatus.DRAFT);

        mvc.perform(get("/api/v1/admin/products")
                        .param("status", "DRAFT")
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-2"));
    }

    @Test
    @DisplayName("detail returns both languages unresolved, so the editor can round-trip them")
    void detailReturnsRawLanguageFields() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Product product = fixtures.product("SKU-1", "هاتف ذكي", "Smartphone",
                new BigDecimal("4999.00"), ProductStatus.ACTIVE);

        // Asked for in English. A locale-resolved DTO would collapse both names to "Smartphone".
        mvc.perform(get("/api/v1/admin/products/" + product.getId())
                        .header("Accept-Language", "en")
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameAr").value("هاتف ذكي"))
                .andExpect(jsonPath("$.nameEn").value("Smartphone"));
    }

    @Test
    @DisplayName("saving an unmodified form does not overwrite the Arabic name")
    void updateRoundTripsBothLanguages() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Product product = fixtures.product("SKU-1", "هاتف ذكي", "Smartphone",
                new BigDecimal("4999.00"), ProductStatus.ACTIVE);

        // Exactly what the form submits after loading the product and editing only the price.
        String body = json.writeValueAsString(Map.of(
                "sku", "SKU-1",
                "nameAr", "هاتف ذكي",
                "nameEn", "Smartphone",
                "basePrice", new BigDecimal("3999.00"),
                "status", "ACTIVE"));

        mvc.perform(put("/api/v1/admin/products/" + product.getId())
                        .header("Accept-Language", "en")
                        .contentType("application/json")
                        .content(body)
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameAr").value("هاتف ذكي"))
                .andExpect(jsonPath("$.nameEn").value("Smartphone"))
                .andExpect(jsonPath("$.basePrice").value(3999.00));
    }

    @Test
    @DisplayName("bulk status publishes several products in one call")
    void bulkStatusPublishes() throws Exception {
        User admin = fixtures.superAdmin("admin@test.local");
        Product first = fixtures.product("SKU-1", "أ", "A", new BigDecimal("10.00"), ProductStatus.DRAFT);
        Product second = fixtures.product("SKU-2", "ب", "B", new BigDecimal("20.00"), ProductStatus.DRAFT);

        mvc.perform(patch("/api/v1/admin/products/bulk-status")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "productIds", java.util.List.of(first.getId(), second.getId()),
                                "status", "ACTIVE")))
                        .with(Auth.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));
    }

    @Test
    @DisplayName("a customer cannot reach the admin catalog")
    void customerIsForbidden() throws Exception {
        User customer = fixtures.customer("shopper@test.local");

        mvc.perform(get("/api/v1/admin/products").with(Auth.as(customer)))
                .andExpect(status().isForbidden());
    }
}
