package com.eshop.catalog.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "brands")
@Getter
@Setter
@NoArgsConstructor
public class Brand extends AuditableEntity {

    @Column(name = "slug", nullable = false, unique = true, length = 140)
    private String slug;

    @Column(name = "name_ar", nullable = false, length = 160)
    private String nameAr;

    @Column(name = "name_en", length = 160)
    private String nameEn;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "description_ar")
    private String descriptionAr;

    @Column(name = "description_en")
    private String descriptionEn;

    @Column(name = "website", length = 255)
    private String website;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_featured", nullable = false)
    private boolean featured = false;

    @Column(name = "position", nullable = false)
    private int position = 0;

    public String name(String locale) {
        return "en".equalsIgnoreCase(locale) && nameEn != null && !nameEn.isBlank() ? nameEn : nameAr;
    }
}
