package com.eshop.settings.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A single store-configuration entry, keyed by a dotted name such as {@code checkout.cod_fee}.
 *
 * <p>These are the knobs an operator changes without a deploy - VAT rate, COD surcharge, whether
 * reviews auto-approve. Values that belong to the <em>environment</em> rather than the business
 * (database hosts, API keys, bucket names) stay in {@code AppProperties} and Secret Manager; putting
 * them here would mean a database row could redirect traffic or leak credentials.
 *
 * <p>{@code isPublic} marks entries safe to expose on the unauthenticated
 * {@code GET /settings/public} endpoint the storefront reads at boot.
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
public class Setting {

    @Id
    @Column(name = "key", nullable = false, length = 80)
    private String key;

    /**
     * Arbitrary JSON - scalar, object, or array - so a setting's shape can evolve.
     *
     * <p>Typed as {@link JsonNode} rather than {@code Object}. Hibernate cannot infer a JSON
     * serialiser for a field declared as {@code Object}: it falls back to treating the value as a
     * {@code String}, which writes {@code Eshop Egypt} instead of {@code "Eshop Egypt"} - rejected by
     * Postgres as malformed JSON - and throws {@code ClassCastException} at flush for anything that
     * is not a String. A concrete node type gives the Jackson format mapper something to work with,
     * so every JSON shape round-trips.
     *
     * <p>Callers outside this package work in plain Java values; {@code SettingsService} converts at
     * the boundary rather than making every reader understand Jackson's tree model.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false, columnDefinition = "jsonb")
    private JsonNode value;

    @Column(name = "description")
    private String description;

    @Column(name = "is_public", nullable = false)
    private boolean publicSetting = false;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
