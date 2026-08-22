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
 * A product photograph stored in Cloud Storage and served through Cloud CDN.
 *
 * <p>{@code width}/{@code height} are recorded so the frontend can reserve the exact box before the
 * image loads, which is what keeps Cumulative Layout Shift at zero. {@code blurhash} is a ~30-byte
 * encoding of the image used as an inline placeholder — it ships in the JSON response, so the
 * placeholder needs no second network round trip.
 */
@Entity
@Table(name = "product_images")
@Getter
@Setter
@NoArgsConstructor
public class ProductImage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Set when the image is specific to one variant, e.g. the red colourway. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "url", nullable = false)
    private String url;

    /** GCS object key, retained so the object can be deleted when the row is. */
    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "alt_ar")
    private String altAr;

    @Column(name = "alt_en")
    private String altEn;

    @Column(name = "position", nullable = false)
    private int position = 0;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "blurhash", length = 64)
    private String blurhash;

    /** Alt text, falling back to the product name so the attribute is never empty. */
    public String alt(String locale) {
        String preferred = "en".equalsIgnoreCase(locale) ? altEn : altAr;
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return product == null ? "" : product.name(locale);
    }
}
