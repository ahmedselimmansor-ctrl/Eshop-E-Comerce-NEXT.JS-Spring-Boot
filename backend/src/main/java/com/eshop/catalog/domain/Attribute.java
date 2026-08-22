package com.eshop.catalog.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A product characteristic - colour, size, storage capacity, material.
 *
 * <p>One definition serves two distinct jobs, distinguished by {@link #variantAxis}:
 * <ul>
 *   <li><b>Variant axis</b> ({@code true}) - the attribute generates purchasable variants. A
 *       T-shirt with colour and size axes yields one {@link ProductVariant} per combination, each
 *       with its own SKU, price, and stock.</li>
 *   <li><b>Descriptive</b> ({@code false}) - the attribute describes the product without splitting
 *       it. Screen size and warranty period belong on the spec table, not on separate SKUs.</li>
 * </ul>
 * Getting this wrong is expensive: marking a descriptive attribute as an axis multiplies the SKU
 * count combinatorially for no commercial reason.
 */
@Entity
@Table(name = "attributes")
@Getter
@Setter
@NoArgsConstructor
public class Attribute extends AuditableEntity {

    public enum Type {
        TEXT,
        NUMBER,
        BOOLEAN,
        SELECT,
        MULTISELECT,
        /** Rendered as a swatch; values carry a {@code hexColor}. */
        COLOR
    }

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name_ar", nullable = false, length = 120)
    private String nameAr;

    @Column(name = "name_en", length = 120)
    private String nameEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private Type type = Type.SELECT;

    @Column(name = "is_variant_axis", nullable = false)
    private boolean variantAxis = false;

    /** Whether the attribute appears as a facet in listing filters. */
    @Column(name = "is_filterable", nullable = false)
    private boolean filterable = true;

    /** Whether its values are indexed into the searchable text of a product document. */
    @Column(name = "is_searchable", nullable = false)
    private boolean searchable = false;

    @Column(name = "is_required", nullable = false)
    private boolean required = false;

    /** Display unit such as {@code GB}, {@code cm}, {@code mAh}. */
    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "position", nullable = false)
    private int position = 0;

    @OneToMany(mappedBy = "attribute", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<AttributeValue> values = new ArrayList<>();

    public String name(String locale) {
        return "en".equalsIgnoreCase(locale) && nameEn != null && !nameEn.isBlank() ? nameEn : nameAr;
    }

    /** Only enumerated types can define a fixed value list. */
    public boolean hasPredefinedValues() {
        return type == Type.SELECT || type == Type.MULTISELECT || type == Type.COLOR;
    }
}
