package com.eshop.settings.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eshop.common.exception.NotFoundException;
import com.eshop.config.RedisConfig;
import com.eshop.settings.domain.Setting;
import com.eshop.settings.repository.SettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes store settings.
 *
 * <p>Reads are cached and consulted on hot paths (every checkout reads the VAT rate and the COD
 * fee), so the accessors below are typed and total: a missing or malformed row falls back to the
 * supplied default rather than throwing. A checkout must not fail because someone deleted a
 * configuration row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SettingRepository settings;

    /** Shared with the web layer, so a setting serialises the same way it is stored. */
    private final ObjectMapper json;

    @Cacheable(value = RedisConfig.CACHE_SETTINGS, key = "'all'")
    @Transactional(readOnly = true)
    public Map<String, Object> all() {
        Map<String, Object> values = new LinkedHashMap<>();
        settings.findAll().forEach(setting -> values.put(setting.getKey(), plain(setting.getValue())));
        return values;
    }

    @Cacheable(value = RedisConfig.CACHE_SETTINGS, key = "'public'")
    @Transactional(readOnly = true)
    public Map<String, Object> publicSettings() {
        Map<String, Object> values = new LinkedHashMap<>();
        settings.findByPublicSettingTrue()
                .forEach(setting -> values.put(setting.getKey(), plain(setting.getValue())));
        return values;
    }

    @Transactional(readOnly = true)
    public List<Setting> list() {
        return settings.findAll();
    }

    @Transactional(readOnly = true)
    public Setting require(String key) {
        return settings.findById(key).orElseThrow(() -> NotFoundException.of("Setting", key));
    }

    /**
     * The stored JSON as an ordinary Java value - {@code String}, {@code Boolean}, {@code Number},
     * {@code List}, or {@code Map}.
     *
     * <p>Conversion happens here, once, so that everything downstream - the typed accessors below,
     * the storefront's public settings payload, every caller of {@link #all()} - keeps working with
     * plain values and never has to know that the column is a Jackson tree underneath.
     */
    private Object plain(JsonNode node) {
        return node == null || node.isNull() ? null : json.convertValue(node, Object.class);
    }

    // ---------------------------------------------------------- typed reads --

    public String string(String key, String fallback) {
        Object value = all().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public boolean bool(String key, boolean fallback) {
        Object value = all().get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    public int integer(String key, int fallback) {
        Object value = all().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            log.warn("setting_not_an_integer key={} value={} using_default={}", key, value, fallback);
            return fallback;
        }
    }

    /**
     * Money and rates. Goes through {@code BigDecimal(String)} rather than {@code valueOf(double)}
     * so a stored {@code 0.14} does not arrive as {@code 0.14000000000000001}.
     */
    public BigDecimal decimal(String key, BigDecimal fallback) {
        Object value = all().get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            log.warn("setting_not_a_number key={} value={} using_default={}", key, value, fallback);
            return fallback;
        }
    }

    // -------------------------------------------------------------- writes --

    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_SETTINGS, allEntries = true)
    public Setting put(String key, Object value, UUID actorId) {
        Setting setting = settings.findById(key).orElseGet(() -> {
            Setting created = new Setting();
            created.setKey(key);
            return created;
        });

        setting.setValue(json.valueToTree(value));
        setting.setUpdatedBy(actorId);
        setting.setUpdatedAt(Instant.now());

        log.info("setting_updated key={} actor={}", key, actorId);
        return settings.save(setting);
    }

    @Transactional
    @CacheEvict(value = RedisConfig.CACHE_SETTINGS, allEntries = true)
    public void putAll(Map<String, Object> values, UUID actorId) {
        values.forEach((key, value) -> put(key, value, actorId));
    }

    // ------------------------------------------------------- known settings --

    public static final String STORE_NAME = "store.name";
    public static final String CHECKOUT_COD_FEE = "checkout.cod_fee";
    public static final String CHECKOUT_TAX_RATE = "checkout.tax_rate";
    public static final String CHECKOUT_MIN_ORDER = "checkout.min_order";
    public static final String CHECKOUT_GUEST_ENABLED = "checkout.guest_enabled";
    public static final String REVIEWS_REQUIRE_PURCHASE = "catalog.reviews_require_purchase";
    public static final String REVIEWS_AUTO_APPROVE = "catalog.reviews_auto_approve";
    public static final String INVENTORY_ALLOW_BACKORDER = "inventory.allow_backorder";
    public static final String ORDER_AUTO_CANCEL_MINUTES = "order.auto_cancel_minutes";
    public static final String ORDER_RETURN_WINDOW_DAYS = "order.return_window_days";
}
