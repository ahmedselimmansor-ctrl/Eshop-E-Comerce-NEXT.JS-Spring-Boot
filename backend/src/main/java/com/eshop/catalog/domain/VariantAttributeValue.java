package com.eshop.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * Binds a variant to one value on one axis: "this variant's Colour is Red".
 *
 * <p>The composite key {@code (variant_id, attribute_id)} is not incidental — it enforces at the
 * database level that a variant cannot claim two colours, which is the invariant the whole variant
 * model rests on.
 */
@Entity
@Table(name = "variant_attribute_values")
@Getter
@Setter
@NoArgsConstructor
public class VariantAttributeValue {

    @EmbeddedId
    private Key id = new Key();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("variantId")
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("attributeId")
    @JoinColumn(name = "attribute_id", nullable = false)
    private Attribute attribute;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_value_id", nullable = false)
    private AttributeValue attributeValue;

    public VariantAttributeValue(ProductVariant variant, AttributeValue attributeValue) {
        this.variant = variant;
        this.attribute = attributeValue.getAttribute();
        this.attributeValue = attributeValue;
        this.id = new Key(variant.getId(), attributeValue.getAttribute().getId());
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Column(name = "variant_id")
        private UUID variantId;

        @Column(name = "attribute_id")
        private UUID attributeId;
    }
}
