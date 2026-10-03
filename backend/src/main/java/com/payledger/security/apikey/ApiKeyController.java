package com.payledger.security.apikey;

import com.payledger.security.Actor;
import com.payledger.security.apikey.ApiKeys.ApiKey;
import com.payledger.security.apikey.ApiKeys.CreatedApiKey;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** API keys for machine clients. Only an ADMIN manages them. */
@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API keys")
class ApiKeyController {

    private final ApiKeys apiKeys;

    ApiKeyController(ApiKeys apiKeys) {
        this.apiKeys = apiKeys;
    }

    /** 201 with the key in {@code key}. This is the only time it is shown. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ApiResponse(responseCode = "201", description = "Issued")
    ResponseEntity<CreatedApiKeyResponse> create(@Valid @RequestBody CreateApiKeyRequest request, Actor actor) {
        CreatedApiKey created = apiKeys.create(request.name(), request.scopes(), request.expiresAt(), actor);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.apiKey().id()).toUri();
        return ResponseEntity.created(location).body(new CreatedApiKeyResponse(created.apiKey(), created.key()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    List<ApiKey> list() {
        return apiKeys.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiKey get(@PathVariable UUID id) {
        return apiKeys.get(id);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    ApiKey revoke(@PathVariable UUID id, Actor actor) {
        return apiKeys.revoke(id, actor);
    }

    /** @param expiresAt optional; PCI DSS asks for credentials of system accounts to be rotated periodically */
    record CreateApiKeyRequest(@NotBlank @Size(max = 100) String name, @NotEmpty Set<ApiKeyScope> scopes,
                               @Future Instant expiresAt) {
    }

    record CreatedApiKeyResponse(ApiKey apiKey, String key) {
    }
}
