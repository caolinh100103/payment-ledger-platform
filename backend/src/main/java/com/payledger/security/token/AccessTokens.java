package com.payledger.security.token;

import com.payledger.security.Role;
import com.payledger.security.SecurityProperties;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Issues short-lived access tokens in the RFC 9068 JWT profile. They are stateless: every service checks the
 * signature and the claims, and nobody looks them up. The flip side is that a token cannot be revoked before it
 * expires, which is why it lives only minutes and a refresh token is used to get the next one.
 */
@Component
public class AccessTokens {

    /** RFC 9068 §2.2.3.1 (borrowed from SCIM): the roles of the subject. */
    public static final String ROLES_CLAIM = "roles";
    static final String TYPE = "at+jwt";

    private final JwtEncoder encoder;
    private final SigningKey signingKey;
    private final SecurityProperties.Jwt properties;

    AccessTokens(JwtEncoder encoder, SigningKey signingKey, SecurityProperties properties) {
        this.encoder = encoder;
        this.signingKey = signingKey;
        this.properties = properties.jwt();
    }

    /** @param sessionId the sign-in session ({@code sid} claim), which ties the token to its refresh tokens */
    public IssuedToken issue(UUID userId, Role role, UUID sessionId) {
        // JWT timestamps are whole seconds.
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).type(TYPE).keyId(signingKey.keyId()).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .audience(List.of(properties.audience()))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("client_id", properties.clientId())
                .claim(ROLES_CLAIM, List.of(role.name()))
                .claim("sid", sessionId.toString())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, Duration.between(now, expiresAt));
    }

    public record IssuedToken(String value, Duration expiresIn) {
    }
}
