package com.eshop.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Web-layer plumbing: locale negotiation, pagination limits, and CORS.
 */
@Configuration
@RequiredArgsConstructor
@EnableSpringDataWebSupport
public class WebConfig implements WebMvcConfigurer {

    private static final Locale ARABIC = Locale.forLanguageTag("ar");
    private static final Locale ENGLISH = Locale.ENGLISH;

    private final AppProperties properties;

    /**
     * Locale comes from {@code Accept-Language} and nothing else - no cookie, no session.
     *
     * <p>The API is stateless and cached at the CDN, so a locale stored server-side would either be
     * ignored or, worse, poison a shared cache entry with the wrong language. Arabic is the default
     * when the header is missing or asks for anything unsupported.
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(ARABIC);
        resolver.setSupportedLocales(List.of(ARABIC, ENGLISH));
        return resolver;
    }

    /**
     * Caps page size so a client cannot ask for {@code ?size=100000} and turn a listing endpoint
     * into an unbounded table scan.
     */
    @Bean
    public PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
        return resolver -> {
            resolver.setMaxPageSize(100);
            resolver.setFallbackPageable(org.springframework.data.domain.PageRequest.of(0, 20));
            resolver.setOneIndexedParameters(false);
        };
    }

    /**
     * CORS for the two browser origins.
     *
     * <p>Origins are enumerated from configuration rather than wildcarded: the refresh-token cookie
     * requires {@code allowCredentials=true}, and the spec forbids pairing that with {@code *}.
     * Mobile clients are not browsers and are unaffected by any of this.
     */
    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        AppProperties.Cors cors = properties.getCors();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(cors.getAllowedOrigins());
        configuration.setAllowedMethods(cors.getAllowedMethods());
        configuration.setAllowedHeaders(cors.getAllowedHeaders());
        configuration.setExposedHeaders(cors.getExposedHeaders());
        configuration.setAllowCredentials(cors.isAllowCredentials());
        configuration.setMaxAge(cors.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        // Payment providers post webhooks server-to-server; no CORS grant is needed or wanted.
        CorsConfiguration denied = new CorsConfiguration();
        source.registerCorsConfiguration("/api/v1/payments/webhooks/**", denied);

        return source;
    }
}
