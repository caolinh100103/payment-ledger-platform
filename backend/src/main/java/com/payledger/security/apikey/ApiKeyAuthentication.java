package com.payledger.security.apikey;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** A request authenticated with an API key. The key itself is not kept, only which key it was. */
public final class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final UUID keyId;
    private final String keyName;

    ApiKeyAuthentication(UUID keyId, String keyName, Set<ApiKeyScope> scopes) {
        super(scopes.stream().map(scope -> new SimpleGrantedAuthority(scope.authority())).collect(Collectors.toSet()));
        this.keyId = keyId;
        this.keyName = keyName;
        setAuthenticated(true);
    }

    public UUID getKeyId() {
        return keyId;
    }

    public String getKeyName() {
        return keyName;
    }

    @Override
    public Object getPrincipal() {
        return keyId;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return keyId.toString();
    }
}
