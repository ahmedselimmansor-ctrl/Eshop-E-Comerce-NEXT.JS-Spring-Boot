package com.eshop.security.jwt;

import com.eshop.config.AppProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * RSA signing material for access tokens.
 *
 * <p>RS256 rather than HS256 so that verification requires only the public key. The API server holds
 * the private key; anything that merely needs to check a token — a future gateway, an internal
 * service, a partner — can be handed the JWKS endpoint instead of a shared secret that would also
 * let it mint tokens.
 *
 * <p>In deployed environments the PEM pair arrives from Secret Manager. When the configuration is
 * blank (local dev, tests) an ephemeral pair is generated at startup: convenient, and it means every
 * restart invalidates outstanding tokens, which is the correct behaviour for a throwaway key.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class JwtKeys {

    private final AppProperties properties;

    @Bean
    public RSAKey rsaKey() {
        AppProperties.Jwt config = properties.getJwt();

        if (config.getPrivateKey().isBlank() || config.getPublicKey().isBlank()) {
            log.warn("jwt_keys_generated_ephemeral — JWT_PRIVATE_KEY/JWT_PUBLIC_KEY are not set. "
                    + "Tokens will not survive a restart. This must never happen outside local development.");
            KeyPair pair = generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        }

        RSAPublicKey publicKey = parsePublicKey(config.getPublicKey());
        RSAPrivateKey privateKey = parsePrivateKey(config.getPrivateKey());

        try {
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    // Stable kid derived from the key itself, so rotating the key changes the kid
                    // and clients can hold both during the overlap window.
                    .keyIDFromThumbprint()
                    .build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Unable to derive a key id from the configured RSA key", e);
        }
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey) {
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build the JWT decoder from the configured key", e);
        }
    }

    // ------------------------------------------------------------------ PEM --

    private static RSAPublicKey parsePublicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPem(pem));
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("JWT_PUBLIC_KEY is not a valid X.509 PEM public key", e);
        }
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPem(pem));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            // The message must not echo any part of the key material.
            throw new IllegalStateException("JWT_PRIVATE_KEY is not a valid PKCS#8 PEM private key", e);
        }
    }

    /** Tolerates headers, footers, and the newline mangling that env vars routinely introduce. */
    private static String stripPem(String pem) {
        return pem.replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("RSA key generation failed", e);
        }
    }
}
