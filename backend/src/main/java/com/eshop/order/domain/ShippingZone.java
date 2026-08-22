package com.eshop.order.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * A group of governorates that share shipping rates.
 *
 * <p>Egypt's delivery economics cluster naturally — Greater Cairo is next-day and cheap, the Delta
 * is two to three days, Upper Egypt and the frontier governorates cost meaningfully more. Zones
 * capture that without a rate row per governorate.
 *
 * <p>The governorate list is a JSONB array with a GIN index, so "which zone covers الجيزة?" is one
 * containment query rather than a join table nobody would ever query the other way round.
 */
@Entity
@Table(name = "shipping_zones")
@Getter
@Setter
@NoArgsConstructor
public class ShippingZone extends AuditableEntity {

    @Column(name = "name_ar", nullable = false, length = 120)
    private String nameAr;

    @Column(name = "name_en", length = 120)
    private String nameEn;

    /** Governorate names exactly as stored on addresses, e.g. {@code ["القاهرة","الجيزة"]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "governorates", nullable = false, columnDefinition = "jsonb")
    private List<String> governorates = List.of();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public boolean covers(String governorate) {
        return governorate != null && governorates.stream().anyMatch(governorate::equalsIgnoreCase);
    }

    public String name(String locale) {
        return "en".equalsIgnoreCase(locale) && nameEn != null && !nameEn.isBlank() ? nameEn : nameAr;
    }
}
