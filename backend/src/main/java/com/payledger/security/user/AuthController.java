package com.payledger.security.user;

import com.payledger.security.AuthenticationFailedException;
import com.payledger.security.Role;
import com.payledger.security.token.AccessTokens;
import com.payledger.security.token.AccessTokens.IssuedToken;
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

/** Public endpoints: signing up and signing in. Everything else needs the token they hand out. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final UserService userService;
    private final LoginService loginService;
    private final AccessTokens accessTokens;

    AuthController(UserService userService, LoginService loginService, AccessTokens accessTokens) {
        this.userService = userService;
        this.loginService = loginService;
        this.accessTokens = accessTokens;
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
            case Succeeded succeeded -> TokenResponse.of(accessTokens.issue(succeeded.user().getId(),
                    succeeded.user().getRole()));
            case Rejected rejected -> throw new AuthenticationFailedException("INVALID_CREDENTIALS",
                    "The username or password is incorrect");
            case Locked locked -> throw new AuthenticationFailedException("ACCOUNT_LOCKED",
                    "Too many failed sign-ins; try again later or contact support",
                    Duration.between(Instant.now(), locked.until()));
        };
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

    /** Field meanings as in an OAuth 2.0 token response (RFC 6749 §5.1); {@code expiresIn} is in seconds. */
    record TokenResponse(String accessToken, String tokenType, long expiresIn) {

        static TokenResponse of(IssuedToken accessToken) {
            return new TokenResponse(accessToken.value(), "Bearer", accessToken.expiresIn().toSeconds());
        }
    }
}
