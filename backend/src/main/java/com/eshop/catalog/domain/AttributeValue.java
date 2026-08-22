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

/** One allowed value of an enumerated {@link Attribute}, e.g. Colour → Red. */
@Entity
@Table(name = "attribute_values")
@Getter
@Setter
@NoArgsConstructor
public class AttributeValue extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", nullable = false)
    private Attribute attribute;

    /** Stable machine code used in filter query parameters ({@code ?attr_color=red}). */
    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "value_ar", nullable = false, length = 160)
    private String valueAr;

    @Column(name = "value_en", length = 160)
    private String valueEn;

    /** Swatch colour for {@link Attribute.Type#COLOR}; {@code #RRGGBB}. */
    @Column(name = "hex_color", length = 7)
    private String hexColor;

    /** Optional pattern or texture swatch, used where a flat colour is not representative. */
    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "position", nullable = false)
    private int position = 0;

    public String value(String locale) {
        return "en".equalsIgnoreCase(locale) && valueEn != null && !valueEn.isBlank() ? valueEn : valueAr;
    }
}
