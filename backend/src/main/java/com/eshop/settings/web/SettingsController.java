package com.eshop.settings.web;

import com.eshop.settings.service.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * The settings a storefront is allowed to know.
 *
 * <p>Only rows flagged {@code is_public} are returned, which is what keeps the moderation and
 * fulfilment switches out of a payload every visitor receives.
 *
 * <p>This exists because several store rules are enforced on the server and were, until now,
 * invisible to the client until it broke one. A cart below {@code checkout.min_order} is rejected at
 * checkout; without this endpoint the first the customer hears of a minimum is a failure after they
 * have filled in an address.
 */
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
@Tag(name = "Settings")
public class SettingsController {

    private final SettingsService settings;

    @GetMapping("/public")
    @Operation(summary = "Public store settings",
            description = "Currency, contact details, the cash-on-delivery surcharge, the minimum "
                    + "order value, and whether guest checkout is open. Nothing marked private.")
    public ResponseEntity<Map<String, Object>> publicSettings() {
        return ResponseEntity.ok()
                // Cached briefly rather than not at all: every page load wants these, and a change
                // that takes five minutes to appear is not worth a query per request.
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(settings.publicSettings());
    }
}
