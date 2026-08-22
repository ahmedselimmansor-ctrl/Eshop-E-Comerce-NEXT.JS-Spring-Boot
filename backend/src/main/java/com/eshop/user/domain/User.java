package com.eshop.user.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A customer or staff account.
 *
 * <p>There is one user table, not separate customer and admin tables: staff place orders too, and
 * duplicating identity would mean duplicating password policy, lockout, and session revocation. The
 * distinction is entirely in {@link Role} membership.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends AuditableEntity {

    /*
     * `citext` in PostgreSQL, so uniqueness and lookups are case-insensitive without a
     * lower() index. Declared explicitly because Hibernate reads citext as an unknown type
     * and would otherwise refuse to validate the schema at startup.
     */
    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    /** Argon2id hash. {@code null} for accounts created purely through Google sign-in. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "locale", nullable = false, length = 5)
    private String locale = "ar";

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency = "EGP";

    @Column(name = "marketing_opt_in", nullable = false)
    private boolean marketingOptIn = false;

    @Column(name = "failed_login_attempts", nullable = false)
    private short failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Eager because every authenticated request needs the authority list, and it is a small set.
     * {@code Role.permissions} is eager for the same reason, giving one join instead of N+1.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();

    // ------------------------------------------------------------- behaviour --

    public String fullName() {
        return lastName == null || lastName.isBlank() ? firstName : firstName + " " + lastName;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE && deletedAt == null;
    }

    /** True while a lockout window from repeated failed logins is still in effect. */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean hasRole(String roleName) {
        return roles.stream().anyMatch(role -> role.getName().equals(roleName));
    }

    public boolean isStaff() {
        return roles.stream().anyMatch(Role::isStaffRole);
    }

    /** Flattened authority names, e.g. {@code product:write}, used to build the security context. */
    public Set<String> permissionNames() {
        return roles.stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> roleNames() {
        return roles.stream().map(Role::getName).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void addRole(Role role) {
        roles.add(role);
    }

    public void recordSuccessfulLogin() {
        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = Instant.now();
    }

    /**
     * Records a failed attempt and locks the account once the threshold is reached.
     *
     * <p>Lockout is time-boxed rather than permanent: a permanent lock hands an attacker a
     * denial-of-service against any account whose email they know.
     */
    public void recordFailedLogin(int maxAttempts, int lockoutMinutes) {
        failedLoginAttempts = (short) (failedLoginAttempts + 1);
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = Instant.now().plusSeconds(lockoutMinutes * 60L);
        }
    }
}
