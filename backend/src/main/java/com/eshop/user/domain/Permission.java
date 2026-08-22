package com.eshop.user.domain;

import com.eshop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single {@code resource:action} capability, e.g. {@code order:refund}.
 *
 * <p>Authorisation checks target permissions, never roles. {@code @PreAuthorize("hasAuthority(
 * 'order:refund')")} keeps working when the customer-service role is later split in two, whereas
 * {@code hasRole('SUPPORT')} would have to be found and edited across every controller.
 *
 * <p>The catalogue is seeded and kept in sync by {@code R__seed_reference_data.sql}.
 */
@Entity
@Table(name = "permissions")
@Getter
@Setter
@NoArgsConstructor
public class Permission extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "resource", nullable = false, length = 32)
    private String resource;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "description_ar", nullable = false)
    private String descriptionAr;

    @Column(name = "description_en")
    private String descriptionEn;
}
