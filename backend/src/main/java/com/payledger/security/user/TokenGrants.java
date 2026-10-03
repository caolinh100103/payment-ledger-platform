package com.payledger.security.user;

import com.payledger.security.AuthenticationFailedException;
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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The two ways to get tokens, named after their OAuth 2.0 grants: a password, or a refresh token. Shared by the API
 * endpoints, which return both tokens in the body, and the browser endpoints, which put the refresh token in a cookie.
 * Every refusal is an {@link AuthenticationFailedException} with a stable code.
 */
@Component
class TokenGrants {

    record Tokens(IssuedToken accessToken, IssuedRefreshToken refreshToken) {
    }

    private final UserService userService;
    private final LoginService loginService;
    private final AccessTokens accessTokens;
    private final Sessions sessions;

    TokenGrants(UserService userService, LoginService loginService, AccessTokens accessTokens, Sessions sessions) {
        this.userService = userService;
        this.loginService = loginService;
        this.accessTokens = accessTokens;
        this.sessions = sessions;
    }

    /** 401 {@code INVALID_CREDENTIALS} for a wrong password and an unknown username alike. */
    Tokens password(String username, String password, String clientIp) {
        return switch (loginService.login(username, password, clientIp)) {
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
    Tokens refreshToken(String refreshToken) {
        return switch (sessions.refresh(refreshToken)) {
            // The role is read again, so a role change takes effect within one access token lifetime.
            case Refreshed refreshed -> tokens(userService.get(refreshed.userId()), refreshed.sessionId(),
                    refreshed.refreshToken());
            case Invalid invalid -> throw invalidRefreshToken();
            case Reused reused -> throw new AuthenticationFailedException("REFRESH_TOKEN_REUSED",
                    "This refresh token was already used, so the session was ended for safety; sign in again");
        };
    }

    static AuthenticationFailedException invalidRefreshToken() {
        return new AuthenticationFailedException("INVALID_REFRESH_TOKEN",
                "The refresh token is invalid or has expired; sign in again");
    }

    private Tokens tokens(UserAccount user, UUID sessionId, IssuedRefreshToken refreshToken) {
        return new Tokens(accessTokens.issue(user.getId(), user.getRole(), sessionId), refreshToken);
    }
}
