package com.payledger.security.user;

import com.payledger.security.Actor;
import com.payledger.security.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
class UserController {

    static final String USERNAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$";

    private final UserService userService;

    UserController(UserService userService) {
        this.userService = userService;
    }

    /** Creates a user with any role, typically a staff member. Customers sign up themselves. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ApiResponse(responseCode = "201", description = "Created")
    ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request, Actor actor) {
        UserAccount user = userService.create(request.username(), request.password(), request.role(), actor);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(user.getId()).toUri();
        return ResponseEntity.created(location).body(UserResponse.from(user));
    }

    /**
     * Finds a user by username, as a support desk does when a customer calls: an exact match, so a list of zero or one.
     */
    @GetMapping
    @PreAuthorize("hasRole('OPERATOR')")
    List<UserResponse> find(@RequestParam @Size(max = 64) String username) {
        return userService.findByUsername(username).map(UserResponse::from).stream().toList();
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OPERATOR', 'AUDITOR', 'ADMIN')")
    UserResponse me(Actor actor) {
        return UserResponse.from(userService.get(UUID.fromString(actor.id())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('OPERATOR')")
    UserResponse get(@PathVariable UUID id) {
        return UserResponse.from(userService.get(id));
    }

    /** Lifts a sign-in lockout early, e.g. once the call centre has identified the customer. */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasRole('OPERATOR')")
    UserResponse unlock(@PathVariable UUID id, Actor actor) {
        return UserResponse.from(userService.unlock(id, actor));
    }

    record CreateUserRequest(
            @NotBlank @Pattern(regexp = USERNAME_PATTERN,
                    message = "must be 3-64 letters, digits, '.', '_' or '-', starting with a letter or digit")
            String username,
            @NotBlank @Size(max = 256) String password,
            @NotNull Role role) {
    }

    /** {@code lockedUntil} is set while sign-ins are blocked after too many wrong passwords. */
    record UserResponse(UUID id, String username, Role role, @Schema(nullable = true) Instant lockedUntil,
                        @Schema(nullable = true) Instant lastLoginAt, Instant createdAt) {

        static UserResponse from(UserAccount user) {
            Instant lockedUntil = user.isLockedAt(Instant.now()) ? user.getLockedUntil() : null;
            return new UserResponse(user.getId(), user.getUsername(), user.getRole(), lockedUntil,
                    user.getLastLoginAt(), user.getCreatedAt());
        }
    }
}
