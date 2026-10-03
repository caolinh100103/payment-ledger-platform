package com.payledger.security.user;

import com.payledger.security.Actor;
import com.payledger.security.Role;
import com.payledger.security.token.Sessions;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoints: signing up, signing in, refreshing and signing out. Everything else needs the access token
 * they hand out. For API clients, which keep the refresh token themselves; the web app uses
 * {@link BrowserSessionController}, which keeps it in a cookie.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
class AuthController {

    private final UserService userService;
    private final TokenGrants grants;
    private final Sessions sessions;

    AuthController(UserService userService, TokenGrants grants, Sessions sessions) {
        this.userService = userService;
        this.grants = grants;
        this.sessions = sessions;
    }

    /** Opens a CUSTOMER profile, standing in for a bank's eKYC onboarding. Staff are created by an ADMIN. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("permitAll()")
    UserController.UserResponse signup(@Valid @RequestBody SignupRequest request, Actor actor) {
        return UserController.UserResponse.from(
                userService.create(request.username(), request.password(), Role.CUSTOMER, actor));
    }

    /** 401 {@code INVALID_CREDENTIALS} for a wrong password and an unknown username alike. */
    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return TokenResponse.of(grants.password(request.username(), request.password(), http.getRemoteAddr()));
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token; the presented one stops working.
     * 401 {@code REFRESH_TOKEN_REUSED} if it had already been exchanged: the session is then revoked.
     */
    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.of(grants.refreshToken(request.refreshToken()));
    }

    /**
     * Signs out: ends the session of the refresh token. Always 204, even for an unknown token (RFC 7009 §2.2), so the
     * endpoint cannot be used to test tokens. Access tokens already issued stay valid until they expire (minutes).
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("permitAll()")
    void logout(@Valid @RequestBody RefreshRequest request) {
        sessions.revoke(request.refreshToken());
    }

    record SignupRequest(
            @NotBlank @Pattern(regexp = UserController.USERNAME_PATTERN,
                    message = "must be 3-64 letters, digits, '.', '_' or '-', starting with a letter or digit")
            String username,
            @NotBlank @Size(max = 256) String password) {
    }

    // Bounded only to cap the work done before hashing; the policy applies when a password is set.
    record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 256) String password) {
    }

    record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
    }

    /**
     * Field meanings as in an OAuth 2.0 token response (RFC 6749 §5.1), plus Keycloak's refresh token lifetime.
     * Durations are in seconds.
     */
    record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
                         long refreshExpiresIn) {

        static TokenResponse of(TokenGrants.Tokens tokens) {
            return new TokenResponse(tokens.accessToken().value(), "Bearer",
                    tokens.accessToken().expiresIn().toSeconds(), tokens.refreshToken().value(),
                    tokens.refreshToken().expiresIn().toSeconds());
        }
    }
}
