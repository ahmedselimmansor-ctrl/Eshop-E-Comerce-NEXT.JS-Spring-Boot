package com.eshop.security.jwt;

import com.eshop.config.AppProperties;
import com.eshop.security.UserPrincipal;
import com.eshop.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues signed access tokens.
 *
 * <p>Refresh tokens are deliberately <em>not</em> JWTs. A JWT refresh token cannot be revoked
 * without keeping a denylist, which reintroduces the server-side state the JWT was supposed to
 * avoid - and refresh tokens are exactly the credential that must be revocable. They are opaque
 * random strings stored hashed in {@code refresh_tokens}; see {@code RefreshTokenService}.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_LOCALE = "locale";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_PERMISSIONS = "perms";
    public static final String CLAIM_TOKEN_TYPE = "typ";

    private static final String TYPE_ACCESS = "access";

    private final JwtEncoder encoder;
    private final AppProperties properties;

    public AccessToken issueAccessToken(User user) {
        AppProperties.Jwt config = properties.getJwt();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(config.getAccessTokenTtlSeconds());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(config.getIssuer())
                .audience(List.of(config.getAudience()))
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                // A unique jti lets an individual token be denylisted in an incident without
                // invalidating every session.
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_NAME, user.fullName())
                .claim(CLAIM_LOCALE, user.getLocale())
                .claim(CLAIM_ROLES, List.copyOf(user.roleNames()))
                .claim(CLAIM_PERMISSIONS, List.copyOf(user.permissionNames()))
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new AccessToken(token, expiresAt, config.getAccessTokenTtlSeconds());
    }

    /** Rebuilds the caller from a verified token. Signature and expiry were already checked. */
    public UserPrincipal toPrincipal(Jwt jwt) {
        return new UserPrincipal(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(CLAIM_EMAIL),
                jwt.getClaimAsString(CLAIM_NAME),
                jwt.getClaimAsString(CLAIM_LOCALE) == null ? "ar" : jwt.getClaimAsString(CLAIM_LOCALE),
                Set.copyOf(orEmpty(jwt.getClaimAsStringList(CLAIM_ROLES))),
                Set.copyOf(orEmpty(jwt.getClaimAsStringList(CLAIM_PERMISSIONS))));
    }

    public boolean isAccessToken(Jwt jwt) {
        return TYPE_ACCESS.equals(jwt.getClaimAsString(CLAIM_TOKEN_TYPE));
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    /**
     * @param value     the encoded JWT
     * @param expiresAt absolute expiry, used to set the client-side refresh timer
     * @param expiresIn lifetime in seconds, as returned in the {@code tokens} payload
     */
    public record AccessToken(String value, Instant expiresAt, long expiresIn) {

        public long secondsRemaining() {
            return Math.max(0, ChronoUnit.SECONDS.between(Instant.now(), expiresAt));
        }
    }
}
