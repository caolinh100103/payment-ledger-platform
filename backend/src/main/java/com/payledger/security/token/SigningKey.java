package com.payledger.security.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.payledger.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.util.UUID;

/**
 * The key that signs access tokens: ECDSA on P-256 with SHA-256 (ES256), one of the three algorithms FAPI 2.0
 * allows for financial APIs (RS256 is not one of them).
 *
 * <p>Only the core holds the private key. Every other service verifies tokens with the public key published at
 * {@code /.well-known/jwks.json}, so a compromised downstream service still cannot mint a token.
 *
 * <p>In production the key comes from a secret store as a JWK with a {@code kid}. Without one, a random key is
 * generated at startup. That is fine for one local instance, but tokens then die with the process and other
 * instances reject them.
 */
@Component
public class SigningKey {

    private static final Logger log = LoggerFactory.getLogger(SigningKey.class);

    private final ECKey jwk;

    SigningKey(SecurityProperties properties) {
        String configured = properties.jwt().signingKey();
        this.jwk = configured == null || configured.isBlank() ? generate() : parse(configured);
    }

    private static ECKey parse(String json) {
        ECKey key;
        try {
            key = ECKey.parse(json);
        } catch (ParseException e) {
            throw new IllegalStateException("payledger.security.jwt.signing-key is not an EC JWK", e);
        }
        if (!Curve.P_256.equals(key.getCurve()) || !key.isPrivate() || key.getKeyID() == null) {
            throw new IllegalStateException("payledger.security.jwt.signing-key must be a private P-256 JWK with a kid");
        }
        return key;
    }

    private static ECKey generate() {
        log.warn("No payledger.security.jwt.signing-key configured: signing with a random key. Tokens will not "
                + "survive a restart and other instances will reject them. Configure a key outside local development.");
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .keyID(UUID.randomUUID().toString())
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate a P-256 key", e);
        }
    }

    ECKey privateJwk() {
        return jwk;
    }

    String keyId() {
        return jwk.getKeyID();
    }

    /** What verifiers need, and nothing more: the public part of the key. */
    public JWKSet publicJwkSet() {
        return new JWKSet(jwk.toPublicJWK());
    }
}
