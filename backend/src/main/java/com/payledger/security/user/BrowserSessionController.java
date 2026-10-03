package com.payledger.security.user;

import com.payledger.security.AuthenticationFailedException;
import com.payledger.security.SecurityProperties;
import com.payledger.security.token.Sessions;
import com.payledger.security.user.AuthController.LoginRequest;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Sign-in for the web app. The same sessions and rotating refresh tokens as {@link AuthController}, but the refresh
 * token never reaches JavaScript: it travels in an {@code HttpOnly} cookie that only these endpoints receive, and the
 * app keeps just the 5-minute access token, in memory. A script injected into the page (XSS) cannot steal a
 * credential that outlives the page. This is the token-mediating backend of the IETF "OAuth 2.0 for Browser-Based
 * Applications" draft (ADR 0015).
 *
 * <p>A cookie is sent by the browser on its own, so these endpoints are guarded against cross-site request forgery
 * twice, as the OWASP CSRF cheat sheet advises: the cookie is {@code SameSite=Strict}, and every request must carry
 * {@code X-Requested-With}. A custom header cannot be added to a cross-origin request without a CORS preflight, which
 * this API never grants. Forging a refresh would gain nothing anyway: the new access token is in the response body,
 * which another origin cannot read.
 */
@RestController
@RequestMapping(BrowserSessionController.PATH)
@Tag(name = "Authentication")
class BrowserSessionController {

    static final String PATH = "/api/v1/auth/browser";
    static final String CSRF_HEADER = "X-Requested-With";

    private final TokenGrants grants;
    private final Sessions sessions;
    private final boolean secureCookie;
    private final String cookieName;

    BrowserSessionController(TokenGrants grants, Sessions sessions, SecurityProperties properties) {
        this.grants = grants;
        this.sessions = sessions;
        this.secureCookie = properties.browser().secureCookie();
        // The __Secure- prefix makes browsers refuse the cookie unless it is Secure and was set over HTTPS.
        this.cookieName = secureCookie ? "__Secure-payledger_refresh" : "payledger_refresh";
    }

    /** As {@code POST /api/v1/auth/login}; the refresh token is set as a cookie instead of returned. */
    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    BrowserTokenResponse login(@CsrfHeader @RequestHeader(name = CSRF_HEADER, required = false) String requestedWith,
                               @Valid @RequestBody LoginRequest request, HttpServletRequest http,
                               HttpServletResponse response) {
        requireCsrfHeader(requestedWith);
        return issue(grants.password(request.username(), request.password(), http.getRemoteAddr()), response);
    }

    /**
     * Exchanges the refresh token cookie for a new access token and a new cookie. Opened again in a new tab or after a
     * reload, the app calls this first to find out whether it is still signed in.
     */
    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    BrowserTokenResponse refresh(@CsrfHeader @RequestHeader(name = CSRF_HEADER, required = false) String requestedWith,
                                 HttpServletRequest http, HttpServletResponse response) {
        requireCsrfHeader(requestedWith);
        try {
            String token = refreshToken(http).orElseThrow(TokenGrants::invalidRefreshToken);
            return issue(grants.refreshToken(token), response);
        } catch (AuthenticationFailedException refused) {
            // The token is expired, unknown or was replayed: stop the browser from sending it. Any other failure
            // (the database unreachable, say) rolled back without using the token, so the cookie stays.
            clearCookie(response);
            throw refused;
        }
    }

    /** Ends the session and deletes the cookie. Always 204, like {@code POST /api/v1/auth/logout}. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("permitAll()")
    void logout(@CsrfHeader @RequestHeader(name = CSRF_HEADER, required = false) String requestedWith,
                HttpServletRequest http, HttpServletResponse response) {
        requireCsrfHeader(requestedWith);
        refreshToken(http).ifPresent(sessions::revoke);
        clearCookie(response);
    }

    private BrowserTokenResponse issue(TokenGrants.Tokens tokens, HttpServletResponse response) {
        // Lives as long as the refresh token in it: the idle timeout, renewed by every refresh.
        setCookie(response, tokens.refreshToken().value(), tokens.refreshToken().expiresIn());
        return new BrowserTokenResponse(tokens.accessToken().value(), "Bearer",
                tokens.accessToken().expiresIn().toSeconds(), tokens.refreshToken().expiresIn().toSeconds());
    }

    private Optional<String> refreshToken(HttpServletRequest http) {
        return Optional.ofNullable(http.getCookies()).stream().flatMap(Arrays::stream)
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private void setCookie(HttpServletResponse response, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                // Sent to these three endpoints only, never with the API calls the access token is for.
                .path(PATH)
                .maxAge(maxAge)
                .build().toString());
    }

    private void clearCookie(HttpServletResponse response) {
        setCookie(response, "", Duration.ZERO);
    }

    /** Checked here rather than by {@code required = true}, to answer 403 with its own code instead of a plain 400. */
    private static void requireCsrfHeader(String requestedWith) {
        if (requestedWith == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                    "Browser session requests must carry the " + CSRF_HEADER + " header");
            problem.setProperty("code", "CSRF_CHECK_FAILED");
            throw new ErrorResponseException(HttpStatus.FORBIDDEN, problem, null);
        }
    }

    /** How the anti-CSRF header appears in the OpenAPI document. */
    @Parameter(required = true, example = "XMLHttpRequest",
            description = "Any value; its presence proves a same-origin request (OWASP custom request header)")
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface CsrfHeader {
    }

    /** As the API's token response, without the refresh token, which is in the cookie. Durations in seconds. */
    record BrowserTokenResponse(String accessToken, String tokenType, long expiresIn, long refreshExpiresIn) {
    }
}
