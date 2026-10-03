package com.payledger.security.token;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * The public signing keys (RFC 7517 JWK Set), at the path OpenID Connect providers such as Keycloak use. The audit
 * and notification services fetch it to verify tokens. A new key can be published here before tokens are signed
 * with it, so that rotating keys never rejects a valid token.
 */
@RestController
class JwksController {

    private final SigningKey signingKey;

    JwksController(SigningKey signingKey) {
        this.signingKey = signingKey;
    }

    @GetMapping("/.well-known/jwks.json")
    @PreAuthorize("permitAll()")
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(signingKey.publicJwkSet().toJSONObject());
    }
}
