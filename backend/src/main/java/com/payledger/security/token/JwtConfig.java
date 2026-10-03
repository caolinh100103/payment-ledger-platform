package com.payledger.security.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.payledger.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
class JwtConfig {

    @Bean
    JwtEncoder jwtEncoder(SigningKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey.privateJwk())));
    }

    /**
     * Accepts only ES256 (no {@code alg: none}, no HMAC with the public key as secret) and validates the token as an
     * RFC 9068 access token: {@code typ: at+jwt}, our issuer and audience, and the required {@code exp}, {@code iat},
     * {@code sub}, {@code jti} and {@code client_id} claims. The {@code typ} check stops another kind of JWT, such as
     * an ID token, from being replayed as an access token.
     */
    @Bean
    JwtDecoder jwtDecoder(SigningKey signingKey, SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(signingKey.publicJwkSet()))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(JwtValidators.createAtJwtValidator()
                .issuer(properties.jwt().issuer())
                .audience(properties.jwt().audience())
                .build());
        return decoder;
    }
}
