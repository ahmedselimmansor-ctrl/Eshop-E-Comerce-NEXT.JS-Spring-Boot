package com.eshop.user.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.util.Hashes;
import com.eshop.config.AppProperties;
import com.eshop.user.domain.OAuthAccount;
import com.eshop.user.domain.Role;
import com.eshop.user.domain.User;
import com.eshop.user.domain.UserStatus;
import com.eshop.user.repository.OAuthAccountRepository;
import com.eshop.user.repository.RoleRepository;
import com.eshop.user.repository.UserRepository;
import com.eshop.user.web.dto.AuthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Google sign-in, implemented directly against the OAuth 2.0 authorization-code flow.
 *
 * <p>Spring Security's {@code oauth2Login} is not used because it wants a servlet session to hold
 * the authorization request between the redirect out and the callback back, and the rest of this API
 * is strictly stateless. Instead the CSRF {@code state} is kept in Redis with a short TTL, which
 * gives the same protection without a session affinity requirement across pods.
 *
 * <p>The callback does not hand tokens to the browser in a URL. Query strings end up in browser
 * history, in the {@code Referer} of the next navigation, and in every proxy log on the path. It
 * issues a single-use code instead, which the frontend exchanges over POST for the real pair.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthService {

    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    private static final String STATE_KEY = "eshop:oauth:state:";
    private static final String CODE_KEY = "eshop:oauth:code:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration CODE_TTL = Duration.ofMinutes(2);

    private final UserRepository users;
    private final RoleRepository roles;
    private final OAuthAccountRepository oauthAccounts;
    private final AuthService authService;
    private final StringRedisTemplate redis;
    private final AppProperties properties;
    private final RestClient restClient = RestClient.create();

    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret:}")
    private String clientSecret;

    /** Builds the Google consent URL and remembers the {@code state} we expect back. */
    public String buildAuthorizationUrl(String returnPath) {
        requireConfigured();

        String state = Hashes.randomToken(24);
        redis.opsForValue().set(STATE_KEY + state,
                returnPath == null || returnPath.isBlank() ? "/" : returnPath, STATE_TTL);

        return UriComponentsBuilder.fromUriString(GOOGLE_AUTH_URL)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("access_type", "online")
                // Google omits the email on repeat consents unless asked to re-prompt.
                .queryParam("prompt", "select_account")
                .build()
                .toUriString();
    }

    /**
     * Handles the redirect back from Google.
     *
     * @return the frontend URL to redirect the browser to, carrying a single-use exchange code
     */
    @Transactional
    public String handleCallback(String code, String state, String userAgent, String ip) {
        String returnPath = redis.opsForValue().getAndDelete(STATE_KEY + state);
        if (returnPath == null) {
            // Unknown or already-used state: either a replay or a forged callback.
            log.warn("oauth_state_rejected ip={}", ip);
            return properties.getWebUrl() + "/login?error=oauth_state";
        }

        GoogleProfile profile;
        try {
            profile = fetchProfile(exchangeCodeForAccessToken(code));
        } catch (RuntimeException e) {
            log.warn("oauth_exchange_failed error={}", e.getMessage());
            return properties.getWebUrl() + "/login?error=oauth_failed";
        }

        User user = findOrCreate(profile);
        AuthResponse session = authService.authenticate(user, userAgent, ip);

        String exchangeCode = Hashes.randomToken(24);
        // Stash only the refresh token; the access token is cheap to re-mint at exchange time and
        // keeping it out of Redis shortens the window in which a cache dump is useful.
        redis.opsForValue().set(CODE_KEY + exchangeCode,
                user.getId() + "|" + session.tokens().refreshToken(), CODE_TTL);

        return UriComponentsBuilder.fromUriString(properties.getWebUrl())
                .path("/auth/callback")
                .queryParam("code", exchangeCode)
                .queryParam("next", returnPath)
                .build()
                .toUriString();
    }

    /** Redeems the single-use code from the callback for a real token pair. */
    @Transactional
    public AuthResponse exchange(String exchangeCode, String userAgent, String ip) {
        String stored = redis.opsForValue().getAndDelete(CODE_KEY + exchangeCode);
        if (stored == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "OAuth exchange code is unknown or spent");
        }

        int separator = stored.indexOf('|');
        UUID userId = UUID.fromString(stored.substring(0, separator));
        String refreshToken = stored.substring(separator + 1);

        User user = users.findActiveById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "User no longer active"));

        // Rotate immediately: the code round-tripped through a browser, so treat the token it
        // carried as already exposed.
        return authService.refresh(refreshToken, userAgent, ip);
    }

    // ----------------------------------------------------------------- google --

    @SuppressWarnings("unchecked") // Google's token endpoint returns a flat JSON object
    private String exchangeCodeForAccessToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri());
        form.add("grant_type", "authorization_code");

        Map<String, Object> response = restClient.post()
                .uri(GOOGLE_TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Google returned no access token");
        }
        return response.get("access_token").toString();
    }

    @SuppressWarnings("unchecked") // OIDC userinfo returns a flat claims object
    private GoogleProfile fetchProfile(String accessToken) {
        Map<String, Object> claims = restClient.get()
                .uri(GOOGLE_USERINFO_URL)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);

        if (claims == null || claims.get("sub") == null || claims.get("email") == null) {
            throw new IllegalStateException("Google profile is missing sub or email");
        }
        return new GoogleProfile(
                claims.get("sub").toString(),
                claims.get("email").toString().toLowerCase(Locale.ROOT),
                Boolean.TRUE.equals(claims.get("email_verified")),
                Optional.ofNullable(claims.get("given_name")).map(Object::toString).orElse("مستخدم"),
                Optional.ofNullable(claims.get("family_name")).map(Object::toString).orElse(null),
                Optional.ofNullable(claims.get("picture")).map(Object::toString).orElse(null),
                claims);
    }

    /**
     * Resolves the Google identity to a local account.
     *
     * <p>Matching is on the provider's subject id first. Falling back to email is only safe because
     * Google asserts {@code email_verified}; linking on an unverified address would let anyone who
     * can create a Google account with a victim's email address walk into that account.
     */
    private User findOrCreate(GoogleProfile profile) {
        Optional<OAuthAccount> linked = oauthAccounts.findByProviderAndProviderUserId(
                OAuthAccount.Provider.GOOGLE, profile.sub());
        if (linked.isPresent()) {
            return linked.get().getUser();
        }

        Optional<User> byEmail = profile.emailVerified()
                ? users.findActiveByEmail(profile.email())
                : Optional.empty();

        User user = byEmail.orElseGet(() -> {
            User created = new User();
            created.setEmail(profile.email());
            created.setFirstName(profile.givenName());
            created.setLastName(profile.familyName());
            created.setAvatarUrl(profile.picture());
            created.setEmailVerified(profile.emailVerified());
            created.setStatus(UserStatus.ACTIVE);
            created.setLocale(properties.getDefaultLocale());
            created.setDefaultCurrency(properties.getDefaultCurrency());
            // No password hash: this account can only be entered through Google until the owner
            // sets one via the password-reset flow.
            created.addRole(roles.findByName(Role.CUSTOMER).orElseThrow(
                    () -> new IllegalStateException("CUSTOMER role missing - seed data not applied")));
            log.info("oauth_user_created provider=GOOGLE email_verified={}", profile.emailVerified());
            return users.save(created);
        });

        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED, "Account is not active");
        }

        OAuthAccount account = new OAuthAccount();
        account.setUser(user);
        account.setProvider(OAuthAccount.Provider.GOOGLE);
        account.setProviderUserId(profile.sub());
        account.setEmail(profile.email());
        account.setRawProfile(profile.claims());
        oauthAccounts.save(account);

        return user;
    }

    private String redirectUri() {
        return properties.getBaseUrl() + "/api/v1/auth/oauth/google/callback";
    }

    private void requireConfigured() {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Google sign-in is not configured (GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET)");
        }
    }

    private record GoogleProfile(String sub, String email, boolean emailVerified, String givenName,
                                 String familyName, String picture, Map<String, Object> claims) {
    }
}
