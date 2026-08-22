package com.eshop.user.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * Link between a local {@link User} and an external identity provider.
 *
 * <p>Kept in its own table so one account can accumulate several sign-in methods over time — a
 * customer who registered with a password can later attach Google without losing their order
 * history, and the provider's subject id is matched rather than the email, because an email
 * address can be reassigned by the provider while the subject id cannot.
 */
@Entity
@Table(name = "oauth_accounts")
@Getter
@Setter
@NoArgsConstructor
public class OAuthAccount extends AuditableEntity {

    public enum Provider {
        GOOGLE,
        APPLE,
        FACEBOOK
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private Provider provider;

    /** The provider's stable subject identifier — never the email. */
    @Column(name = "provider_user_id", nullable = false, length = 191)
    private String providerUserId;

    /*
     * `citext` in PostgreSQL, so uniqueness and lookups are case-insensitive without a
     * lower() index. Declared explicitly because Hibernate reads citext as an unknown type
     * and would otherwise refuse to validate the schema at startup.
     */
    @Column(name = "email", columnDefinition = "citext")
    private String email;

    /** Raw claims kept for support and debugging; contains no tokens. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_profile", columnDefinition = "jsonb")
    private Map<String, Object> rawProfile;
}
