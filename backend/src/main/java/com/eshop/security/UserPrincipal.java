package com.eshop.security;

import com.eshop.user.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, reconstructed from the access token on every request.
 *
 * <p>Built purely from token claims - no database read on the hot path. The trade-off is that a
 * permission change takes effect when the access token next rotates rather than instantly; with a
 * 15-minute access-token lifetime that window is acceptable, and the cases that must be immediate
 * (suspension, password change) revoke the refresh-token family, which cuts the session off at the
 * next refresh.
 *
 * @param id          user id, also the JWT {@code sub}
 * @param email       used for audit rows and support lookups
 * @param fullName    display name
 * @param locale      {@code ar} or {@code en}, drives localisation of generated documents
 * @param roles       role names, for coarse checks such as "is this a staff member"
 * @param permissions fine-grained {@code resource:action} authorities
 */
public record UserPrincipal(
        UUID id,
        String email,
        String fullName,
        String locale,
        Set<String> roles,
        Set<String> permissions
) {

    public static UserPrincipal from(User user) {
        return new UserPrincipal(
                user.getId(),
                user.getEmail(),
                user.fullName(),
                user.getLocale(),
                user.roleNames(),
                user.permissionNames());
    }

    /**
     * Authorities exposed to Spring Security.
     *
     * <p>Permissions are added verbatim ({@code order:refund}) and roles with the conventional
     * {@code ROLE_} prefix, so both {@code hasAuthority('order:refund')} and {@code hasRole('ADMIN')}
     * work. Endpoints should prefer the permission form - see {@code docs/03-CONVENTIONS.md}.
     */
    public Collection<GrantedAuthority> authorities() {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        return List.copyOf(authorities);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public boolean isStaff() {
        return roles.stream().anyMatch(role -> !"CUSTOMER".equals(role));
    }
}
