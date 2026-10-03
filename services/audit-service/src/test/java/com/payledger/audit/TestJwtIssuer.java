package com.payledger.audit;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Stands in for the core service: serves a JWK Set over real HTTP and signs access tokens shaped like the core's,
 * so these tests go through the same key download and claim checks as production.
 */
public class TestJwtIssuer implements AutoCloseable {

    static final String ISSUER = "http://localhost:8080";
    static final String AUDIENCE = "payledger-api";

    private final ECKey key;
    private final HttpServer server;

    TestJwtIssuer() throws Exception {
        key = newKey();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    String jwksUri() {
        return "http://localhost:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    /** An Authorization header value. */
    String bearer(String userId, String role) {
        return "Bearer " + token(userId, role, claims -> claims);
    }

    String token(String userId, String role, UnaryOperator<JWTClaimsSet.Builder> customize) {
        return sign(key, userId, role, customize);
    }

    /** A token that looks right but is signed by a key the JWK Set does not contain. */
    String forgedToken(String userId, String role) throws Exception {
        return sign(newKey(), userId, role, claims -> claims);
    }

    private static String sign(ECKey signingKey, String userId, String role,
                               UnaryOperator<JWTClaimsSet.Builder> customize) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .claim("client_id", "payledger-app")
                .claim("roles", List.of(role))
                .claim("sid", UUID.randomUUID().toString());
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("at+jwt"))
                .keyID(signingKey.getKeyID()).build(), customize.apply(claims).build());
        try {
            jwt.sign(new ECDSASigner(signingKey));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static ECKey newKey() throws Exception {
        return new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256)
                .keyID(UUID.randomUUID().toString()).generate();
    }

    @Override
    public void close() throws IOException {
        server.stop(0);
    }
}
