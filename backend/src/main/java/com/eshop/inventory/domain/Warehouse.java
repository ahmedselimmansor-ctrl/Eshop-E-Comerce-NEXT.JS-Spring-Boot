package com.eshop.inventory.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A physical stock location.
 *
 * <p>Modelled from the start even though the store launches with a single warehouse: retrofitting
 * multi-location stock later means rewriting every reservation and fulfilment query. With the table
 * in place, opening an Alexandria warehouse is a data change.
 */
@Entity
@Table(name = "warehouses")
@Getter
@Setter
@NoArgsConstructor
public class Warehouse extends AuditableEntity {

    @Column(name = "code", nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "name_ar", nullable = false, length = 120)
    private String nameAr;

    @Column(name = "name_en", length = 120)
    private String nameEn;

    @Column(name = "governorate", length = 60)
    private String governorate;

    @Column(name = "city", length = 80)
    private String city;

    @Column(name = "address")
    private String address;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Allocation order - lower is preferred. Ties are broken by warehouse code. */
    @Column(name = "priority", nullable = false)
    private int priority = 0;

    public String name(String locale) {
        return "en".equalsIgnoreCase(locale) && nameEn != null && !nameEn.isBlank() ? nameEn : nameAr;
    }
}
