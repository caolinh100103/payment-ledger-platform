package com.payledger.security.user;

import com.payledger.security.AuthenticationFailedException;
import com.payledger.security.Role;
import com.payledger.security.token.AccessTokens;
import com.payledger.security.token.AccessTokens.IssuedToken;
import com.payledger.security.token.Sessions;
import com.payledger.security.token.Sessions.Invalid;
import com.payledger.security.token.Sessions.IssuedRefreshToken;
import com.payledger.security.token.Sessions.Refreshed;
import com.payledger.security.token.Sessions.Reused;
import com.payledger.security.token.Sessions.Started;
import com.payledger.security.user.LoginService.Locked;
import com.payledger.security.user.LoginService.Rejected;
import com.payledger.security.user.LoginService.Succeeded;
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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Public endpoints: signing up, signing in, refreshing and signing out. Everything else needs the access token
 * they hand out.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final UserService userService;
    private final LoginService loginService;
    private final AccessTokens accessTokens;
    private final Sessions sessions;

    AuthController(UserService userService, LoginService loginService, AccessTokens accessTokens, Sessions sessions) {
        this.userService = userService;
        this.loginService = loginService;
        this.accessTokens = accessTokens;
        this.sessions = sessions;
    }

    /** Opens a CUSTOMER profile, standing in for a bank's eKYC onboarding. Staff are created by an ADMIN. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("permitAll()")
    UserController.UserResponse signup(@Valid @RequestBody SignupRequest request) {
        return UserController.UserResponse.from(userService.create(request.username(), request.password(), Role.CUSTOMER));
    }

    /** 401 {@code INVALID_CREDENTIALS} for a wrong password and an unknown username alike. */
    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return switch (loginService.login(request.username(), request.password())) {
            case Succeeded succeeded -> {
                Started session = sessions.start(succeeded.user().getId());
                yield tokens(succeeded.user(), session.sessionId(), session.refreshToken());
            }
            case Rejected rejected -> throw new AuthenticationFailedException("INVALID_CREDENTIALS",
                    "The username or password is incorrect");
            case Locked locked -> throw new AuthenticationFailedException("ACCOUNT_LOCKED",
                    "Too many failed sign-ins; try again later or contact support",
                    Duration.between(Instant.now(), locked.until()));
        };
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token; the presented one stops working.
     * 401 {@code REFRESH_TOKEN_REUSED} if it had already been exchanged: the session is then revoked.
     */
    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return switch (sessions.refresh(request.refreshToken())) {
            // The role is read again, so a role change takes effect within one access token lifetime.
            case Refreshed refreshed -> tokens(userService.get(refreshed.userId()), refreshed.sessionId(),
                    refreshed.refreshToken());
            case Invalid invalid -> throw new AuthenticationFailedException("INVALID_REFRESH_TOKEN",
                    "The refresh token is invalid or has expired; sign in again");
            case Reused reused -> throw new AuthenticationFailedException("REFRESH_TOKEN_REUSED",
                    "This refresh token was already used, so the session was ended for safety; sign in again");
        };
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

    private TokenResponse tokens(UserAccount user, UUID sessionId, IssuedRefreshToken refreshToken) {
        return TokenResponse.of(accessTokens.issue(user.getId(), user.getRole(), sessionId), refreshToken);
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

        static TokenResponse of(IssuedToken accessToken, IssuedRefreshToken refreshToken) {
            return new TokenResponse(accessToken.value(), "Bearer", accessToken.expiresIn().toSeconds(),
                    refreshToken.value(), refreshToken.expiresIn().toSeconds());
        }
    }
}
