package com.eshop.catalog.domain;

import com.eshop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A descriptive specification on a product — screen size, warranty, material.
 *
 * <p>Distinct from {@link VariantAttributeValue}, which selects a purchasable axis. This one only
 * populates the specification table and the filter facets.
 *
 * <p>Either {@code attributeValue} (for enumerated attributes) or {@code customValue} (for free
 * text and numbers) is set; a check constraint requires exactly one to be present.
 */
@Entity
@Table(name = "product_attribute_values")
@Getter
@Setter
@NoArgsConstructor
public class ProductAttributeValue extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", nullable = false)
    private Attribute attribute;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attribute_value_id")
    private AttributeValue attributeValue;

    @Column(name = "custom_value")
    private String customValue;

    public String display(String locale) {
        if (attributeValue != null) {
            return attributeValue.value(locale);
        }
        String unit = attribute.getUnit();
        return unit == null || unit.isBlank() ? customValue : customValue + " " + unit;
    }
}
