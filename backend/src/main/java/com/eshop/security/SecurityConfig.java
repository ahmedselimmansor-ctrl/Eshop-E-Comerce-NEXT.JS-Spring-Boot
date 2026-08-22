package com.eshop.security;

import com.eshop.common.exception.ErrorCode;
import com.eshop.common.web.ProblemResponseWriter;
import com.eshop.config.AppProperties;
import com.eshop.security.jwt.PrincipalAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * HTTP security policy.
 *
 * <p>Two chains: a permissive one for the payment webhook endpoints, which authenticate by HMAC
 * signature rather than by token, and the main API chain. Splitting them means the webhook path
 * never has to be listed as an exception inside a chain that also carries CSRF and authentication
 * rules - it simply is not subject to them.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private static final String API = "/api/v1";

    private final AppProperties properties;
    private final PrincipalAuthenticationConverter principalConverter;
    private final ProblemResponseWriter problemWriter;
    private final UrlBasedCorsConfigurationSource corsConfigurationSource;

    /**
     * Argon2id password hashing.
     *
     * <p>Parameters follow the OWASP Password Storage Cheat Sheet (m=19456 KiB, t=2, p=1). Argon2id
     * rather than bcrypt because it is memory-hard: bcrypt's small fixed memory footprint lets a GPU
     * or ASIC test candidates in parallel far more cheaply.
     *
     * <p>The encoder writes its parameters into the hash string, so raising the cost later does not
     * invalidate existing hashes - old ones keep verifying and are upgraded on next successful login.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        AppProperties.Security.Argon2 config = properties.getSecurity().getArgon2();
        return new Argon2PasswordEncoder(
                config.getSaltLength(),
                config.getHashLength(),
                config.getParallelism(),
                config.getMemoryKb(),
                config.getIterations());
    }

    /**
     * Webhook chain - first by order, matched only for the two provider callback paths.
     *
     * <p>No authentication, no CSRF, no session: the provider is a server that cannot present a
     * token or a cookie. Authenticity is established inside the controller by verifying the
     * provider's HMAC over the raw body, and replay is prevented by the unique
     * {@code (provider, event_id)} constraint on {@code payment_webhook_events}.
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain webhookFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(API + "/payments/webhooks/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny));
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                // The API authenticates with a Bearer token, which a browser does not attach
                // automatically, so there is no cross-site request forgery to defend against. The
                // one cookie in play - the refresh token - is SameSite=Strict and scoped to
                // /api/v1/auth, and the refresh endpoint additionally requires a POST with a
                // matching body or header.
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .anonymous(Customizer.withDefaults())

                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .preload(true)
                                .maxAgeInSeconds(63_072_000))
                        .permissionsPolicyHeader(permissions -> permissions
                                .policy("geolocation=(), microphone=(), camera=(), payment=()"))
                        // The API only ever returns JSON; a restrictive CSP costs nothing and
                        // neutralises content sniffing on an error page.
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'none'; frame-ancestors 'none'; sandbox")))

                .authorizeHttpRequests(auth -> auth
                        // ---- infrastructure -------------------------------------------------
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/.well-known/jwks.json").permitAll()

                        // ---- authentication --------------------------------------------------
                        .requestMatchers(API + "/auth/**").permitAll()

                        // ---- public catalog --------------------------------------------------
                        .requestMatchers(HttpMethod.GET,
                                API + "/products/**",
                                API + "/categories/**",
                                API + "/brands/**",
                                API + "/attributes/**",
                                API + "/search/**",
                                API + "/shipping/methods",
                                API + "/settings/public").permitAll()
                        .requestMatchers(HttpMethod.POST, API + "/search/click").permitAll()
                        .requestMatchers(HttpMethod.GET, API + "/orders/track/**").permitAll()

                        // A shared wishlist is readable by whoever holds the link. The token is a
                        // 128-bit capability; everything else under /wishlist stays authenticated.
                        .requestMatchers(HttpMethod.GET, API + "/wishlist/shared/*").permitAll()

                        // ---- guest-capable flows ---------------------------------------------
                        // Cart and checkout accept an X-Session-Id instead of a token; ownership is
                        // enforced in the service layer against either the user id or that session.
                        .requestMatchers(API + "/cart/**").permitAll()
                        .requestMatchers(API + "/checkout/**").permitAll()
                        .requestMatchers(HttpMethod.GET, API + "/payments/*/status").permitAll()

                        // ---- authenticated ---------------------------------------------------
                        .requestMatchers(API + "/me/**").authenticated()
                        .requestMatchers(HttpMethod.POST, API + "/products/*/reviews").authenticated()
                        .requestMatchers(API + "/reviews/**").authenticated()

                        // ---- admin ------------------------------------------------------------
                        // Coarse gate only; each endpoint carries its own @PreAuthorize permission
                        // check, which is what actually distinguishes a catalog manager from a
                        // refund-capable order manager.
                        .requestMatchers(API + "/admin/**").authenticated()

                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(principalConverter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            log.debug("jwt_rejected path={} reason={}",
                                    request.getRequestURI(), exception.getMessage());
                            problemWriter.write(request, response, ErrorCode.UNAUTHENTICATED);
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                problemWriter.write(request, response, ErrorCode.FORBIDDEN)))

                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                problemWriter.write(request, response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, exception) ->
                                problemWriter.write(request, response, ErrorCode.FORBIDDEN)));

        return http.build();
    }

    /** Fallback used only when the problem writer itself cannot run. */
    @Bean
    public HttpStatusEntryPoint unauthorizedEntryPoint() {
        return new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    /** Origins that may embed a Paymob payment iframe, referenced by the storefront CSP. */
    public List<String> paymentFrameOrigins() {
        return List.of("https://accept.paymob.com", "https://atfawry.fawrystaging.com", "https://atfawry.com");
    }
}
