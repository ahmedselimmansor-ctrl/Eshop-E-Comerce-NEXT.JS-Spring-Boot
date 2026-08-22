package com.eshop.settings;

import com.eshop.settings.service.SettingsService;
import com.eshop.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settings must survive a write and a read with their JSON type intact.
 *
 * <p>Regression test. {@code Setting.value} was once declared as a bare {@code Object} with
 * {@code @JdbcTypeCode(SqlTypes.JSON)}. Hibernate cannot infer a serialiser for {@code Object} and
 * falls back to treating the value as a {@code String}, so a string was written as bare text and
 * rejected by Postgres as malformed JSON, while every non-string threw {@code ClassCastException} at
 * flush. Every setting write failed, and none of it was visible at compile time.
 */
@SuppressWarnings("unchecked")
class SettingsJsonRoundTripTest extends IntegrationTest {

    @Autowired SettingsService settings;

    @Test
    @DisplayName("every JSON shape round-trips with its type intact")
    void roundTripsEveryShape() {
        UUID actor = fixtures.superAdmin("root@test.local").getId();

        settings.putAll(Map.of(
                "test.string", "Eshop Egypt",
                "test.integer", 25,
                "test.decimal", new BigDecimal("0.14"),
                "test.boolean", true,
                "test.object", Map.of("city", "Cairo", "governorate", "القاهرة"),
                "test.array", List.of("a", "b", "c")
        ), actor);

        Map<String, Object> all = settings.all();

        assertThat(all.get("test.string")).isEqualTo("Eshop Egypt");
        assertThat(all.get("test.integer")).isEqualTo(25);
        assertThat(all.get("test.boolean")).isEqualTo(true);
        assertThat(all.get("test.object"))
                .isInstanceOf(Map.class)
                .satisfies(value -> assertThat((Map<String, Object>) value)
                        .containsEntry("city", "Cairo"));
        assertThat(all.get("test.array")).isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    @DisplayName("Arabic text is stored as JSON, not mangled")
    void preservesArabic() {
        UUID actor = fixtures.superAdmin("root@test.local").getId();
        settings.putAll(Map.of("test.name_ar", "متجر مصر"), actor);

        assertThat(settings.string("test.name_ar", "")).isEqualTo("متجر مصر");
    }

    @Test
    @DisplayName("typed accessors read the stored JSON rather than its text form")
    void typedAccessorsUnwrapJson() {
        UUID actor = fixtures.superAdmin("root@test.local").getId();

        settings.putAll(Map.of(
                "test.fee", new BigDecimal("25.00"),
                "test.flag", false,
                "test.count", 7,
                "test.label", "plain"
        ), actor);

        // decimal() must not see quotes: a quoted "25.00" would still parse, but a quoted value is
        // the symptom of the value having been stored as a JSON string rather than a JSON number.
        assertThat(settings.decimal("test.fee", BigDecimal.ONE)).isEqualByComparingTo("25.00");
        assertThat(settings.bool("test.flag", true)).isFalse();
        assertThat(settings.integer("test.count", 0)).isEqualTo(7);
        assertThat(settings.string("test.label", "")).isEqualTo("plain");
    }

    @Test
    @DisplayName("an existing setting is updated, not duplicated")
    void updatesInPlace() {
        UUID actor = fixtures.superAdmin("root@test.local").getId();

        settings.putAll(Map.of("test.key", "first"), actor);
        settings.putAll(Map.of("test.key", "second"), actor);

        assertThat(settings.string("test.key", "")).isEqualTo("second");
        assertThat(settings.list().stream().filter(s -> s.getKey().equals("test.key")).count())
                .isEqualTo(1);
    }
}
