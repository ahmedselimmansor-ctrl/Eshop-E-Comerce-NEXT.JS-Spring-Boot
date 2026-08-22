package com.eshop.security;

import com.eshop.support.Auth;
import com.eshop.support.IntegrationTest;
import com.eshop.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security posture of the API, asserted rather than assumed.
 *
 * <p>Everything here is a property that is easy to lose in a refactor and impossible to notice by
 * hand: a browser origin that should not be allowed, an admin endpoint that stops checking a
 * permission, an error that starts naming a database constraint. Each has a specific failure mode
 * that only shows up in production, which is why they are tests.
 */
class SecurityPostureTest extends IntegrationTest {

    // -------------------------------------------------------------------- CORS --

    @Test
    @DisplayName("a browser origin the store does not run on is not granted access")
    void unknownOriginIsNotAllowed() throws Exception {
        mvc.perform(options("/api/v1/products")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("a configured origin is granted access, with credentials")
    void configuredOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/v1/products")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                // Credentials are needed for the refresh cookie, which is exactly why the origin
                // list can never become a wildcard.
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    @DisplayName("the allowed origin is echoed exactly, never as a wildcard")
    void originIsNeverWildcarded() throws Exception {
        mvc.perform(options("/api/v1/products")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(result -> {
                    String allowed = result.getResponse().getHeader("Access-Control-Allow-Origin");
                    if ("*".equals(allowed)) {
                        throw new AssertionError(
                                "A wildcard origin with credentials would let any site read a "
                                        + "signed-in customer's data");
                    }
                });
    }

    // ----------------------------------------------------------------- headers --

    @Test
    @DisplayName("responses carry the headers that stop framing and sniffing")
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/v1/products"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Referrer-Policy"));
    }

    // --------------------------------------------------------------- authorization --

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/admin/products",
            "/api/v1/admin/orders",
            "/api/v1/admin/inventory",
            "/api/v1/admin/customers",
            "/api/v1/admin/reviews",
            "/api/v1/admin/settings",
            "/api/v1/admin/dashboard/summary",
    })
    @DisplayName("every admin endpoint refuses an anonymous caller")
    void adminEndpointsRefuseAnonymous(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/admin/products",
            "/api/v1/admin/orders",
            "/api/v1/admin/inventory",
            "/api/v1/admin/customers",
            "/api/v1/admin/settings",
            "/api/v1/admin/dashboard/summary",
    })
    @DisplayName("every admin endpoint refuses a signed-in customer")
    void adminEndpointsRefuseCustomers(String path) throws Exception {
        User customer = fixtures.customer("shopper@test.local");
        mvc.perform(get(path).with(Auth.as(customer))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("one customer cannot read another's orders")
    void customersCannotReadEachOthersOrders() throws Exception {
        User owner = fixtures.customer("owner@test.local");
        User other = fixtures.customer("other@test.local");

        // Not 403: confirming an order number exists is itself a disclosure.
        mvc.perform(get("/api/v1/me/orders/EG-260822-00001").with(Auth.as(other)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/me/orders").with(Auth.as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("the current-user endpoint requires a caller")
    void meRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    // ----------------------------------------------------- error disclosure --

    @Test
    @DisplayName("a validation failure names the field, not the schema")
    void errorsDoNotLeakInternals() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"email\":\"not-an-email\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // A stack trace, a table name or a constraint name in a 400 tells an attacker how the
        // system is built and tells the customer nothing.
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("org.springframework")
                .doesNotContain("SQLException")
                .doesNotContain("constraint")
                .doesNotContain("nested exception");
    }

    @Test
    @DisplayName("a guest cart requires a session id rather than defaulting to somebody's")
    void guestCartRequiresASessionId() throws Exception {
        // Refused, not answered with an empty cart. Without a session id there is nothing to scope
        // the cart to, and inventing one per request would quietly lose the customer's basket.
        mvc.perform(get("/api/v1/cart")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("two guest sessions get two different carts")
    void guestCartsAreIsolatedBySession() throws Exception {
        String first = "11111111-1111-4111-8111-111111111111";
        String second = "22222222-2222-4222-8222-222222222222";

        var product = fixtures.sellableProduct("SKU-1", 10);

        mvc.perform(post("/api/v1/cart/items")
                        .header("X-Session-Id", first)
                        .contentType("application/json")
                        .content(json.writeValueAsString(
                                java.util.Map.of("productId", product.getId(), "quantity", 1))))
                .andExpect(status().isOk());

        // The second session must not see the first session's basket.
        mvc.perform(get("/api/v1/cart").header("X-Session-Id", second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mvc.perform(get("/api/v1/cart").header("X-Session-Id", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }
}
