package com.eshop.user.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named bundle of {@link Permission}s.
 *
 * <p>System roles ({@code is_system}) are seeded and cannot be deleted through the admin API - a
 * missing {@code CUSTOMER} role would break registration for everyone. Custom roles may be created
 * freely by anyone holding {@code role:write}.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class Role extends AuditableEntity {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String SUPPORT = "SUPPORT";
    public static final String CATALOG_MANAGER = "CATALOG_MANAGER";
    public static final String ORDER_MANAGER = "ORDER_MANAGER";
    public static final String ADMIN = "ADMIN";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    @Column(name = "name", nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "description_ar", nullable = false)
    private String descriptionAr;

    @Column(name = "description_en")
    private String descriptionEn;

    @Column(name = "is_system", nullable = false)
    private boolean system = false;

    /**
     * Eagerly fetched because a role is only ever loaded in order to read its permissions - during
     * authentication, to build the authority list. Lazy loading here would guarantee an N+1 on the
     * hottest path in the application.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new LinkedHashSet<>();

    public boolean isStaffRole() {
        return !CUSTOMER.equals(name);
    }
}
