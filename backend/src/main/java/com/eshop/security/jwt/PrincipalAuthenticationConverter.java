package com.eshop.security.jwt;

import com.eshop.common.web.RequestContext;
import com.eshop.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a verified {@link Jwt} into an authentication whose principal is a {@link UserPrincipal}.
 *
 * <p>Controllers then take {@code @AuthenticationPrincipal UserPrincipal caller} and get a typed
 * object rather than reaching into raw claims, which keeps claim names in one place.
 *
 * <p>The user id is also pushed into the MDC here so every subsequent log line in the request — and
 * every audit row — carries it without any further plumbing.
 */
@Component
@RequiredArgsConstructor
public class PrincipalAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtService jwtService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UserPrincipal principal = jwtService.toPrincipal(jwt);
        MDC.put(RequestContext.MDC_USER_ID, principal.id().toString());

        return new UsernamePasswordAuthenticationToken(principal, jwt, principal.authorities()) {
            @Override
            public String getName() {
                // Used as the rate-limit bucket identity and by `authentication.name` in SpEL.
                return principal.id().toString();
            }
        };
    }
}
