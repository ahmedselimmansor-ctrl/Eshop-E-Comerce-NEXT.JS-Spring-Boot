package com.eshop.settings;

import com.eshop.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public settings payload.
 *
 * <p>The important assertion here is the negative one. This response goes to every visitor, signed
 * in or not, so a private row appearing in it is a leak rather than a cosmetic bug, and the
 * {@code is_public} flag is the only thing standing between the two.
 */
class PublicSettingsApiTest extends IntegrationTest {

    @Test
    @DisplayName("returns the store rules a client needs, without authentication")
    void servesPublicSettingsAnonymously() throws Exception {
        mvc.perform(get("/api/v1/settings/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['store.currency']").value("EGP"))
                .andExpect(jsonPath("$['checkout.min_order']").value(50.00))
                .andExpect(jsonPath("$['checkout.cod_fee']").value(20.00))
                .andExpect(jsonPath("$['checkout.guest_enabled']").value(true));
    }

    @Test
    @DisplayName("private settings are not in the payload")
    void withholdsPrivateSettings() throws Exception {
        mvc.perform(get("/api/v1/settings/public"))
                .andExpect(status().isOk())
                // Moderation and fulfilment switches: operational, and nobody's business but staff's.
                .andExpect(jsonPath("$['catalog.reviews_auto_approve']").doesNotExist())
                .andExpect(jsonPath("$['catalog.reviews_require_purchase']").doesNotExist())
                .andExpect(jsonPath("$['inventory.allow_backorder']").doesNotExist())
                .andExpect(jsonPath("$['inventory.low_stock_threshold']").doesNotExist())
                .andExpect(jsonPath("$['order.auto_cancel_minutes']").doesNotExist());
    }

    @Test
    @DisplayName("values keep their JSON type rather than arriving as strings")
    void preservesJsonTypes() throws Exception {
        mvc.perform(get("/api/v1/settings/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['checkout.guest_enabled']").isBoolean())
                .andExpect(jsonPath("$['store.locales']").isArray())
                .andExpect(jsonPath("$['store.name']").isMap());
    }
}
