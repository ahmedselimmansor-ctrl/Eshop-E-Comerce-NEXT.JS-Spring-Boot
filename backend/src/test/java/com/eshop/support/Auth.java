package com.eshop.support;

import com.eshop.security.UserPrincipal;
import com.eshop.user.domain.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Authenticates a MockMvc request as a given user.
 *
 * <p>Builds the same {@link UserPrincipal} the JWT filter would, from the user's real roles and
 * permissions, so {@code @PreAuthorize} is exercised exactly as it is in production. Minting a token
 * and replaying it would test the signing library; this tests the authorization rules, which is
 * where the interesting mistakes are.
 */
public final class Auth {

    private Auth() {
    }

    public static RequestPostProcessor as(User user) {
        UserPrincipal principal = UserPrincipal.from(user);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, "n/a", principal.authorities()));
    }
}
