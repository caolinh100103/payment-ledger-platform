package com.payledger.security.user;

import com.payledger.support.ApiTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.net.HttpCookie;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The web app's sign-in (ADR 0015): the refresh token lives in an HttpOnly cookie scoped to the browser endpoints, is
 * rotated like any refresh token, and none of the three endpoints acts without the anti-CSRF header.
 */
class BrowserSessionApiIntegrationTest extends ApiTestSupport {

    private static final String COOKIE = "__Secure-payledger_refresh";
    private static final String CSRF = "X-Requested-With";

    private String username;

    @BeforeEach
    void signUpCustomer() {
        username = uniqueUsername();
        signUp(username, PASSWORD);
    }

    @Test
    void signInSetsAnHttpOnlyStrictCookieAndKeepsTheRefreshTokenOutOfTheBody() {
        MvcTestResult result = browserLogin(username, PASSWORD);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.accessToken").isNotNull();
        assertThat(result).bodyJson().extractingPath("$.refreshExpiresIn").isEqualTo(900);
        assertThat(bodyOf(result)).doesNotContain("refreshToken");

        String setCookie = result.getMvcResult().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(COOKIE + "=")
                .contains("; Path=/api/v1/auth/browser", "; Max-Age=900", "; Secure", "; HttpOnly",
                        "; SameSite=Strict");
    }

    @Test
    void theAccessTokenWorksOnTheApi() {
        String accessToken = jsonPath(browserLogin(username, PASSWORD), "$.accessToken");

        assertThat(mvc.get().uri("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .hasStatusOk()
                .bodyJson().extractingPath("$.username").isEqualTo(username);
    }

    @Test
    void refreshRotatesTheCookie() {
        String first = refreshCookie(browserLogin(username, PASSWORD));

        MvcTestResult refreshed = browserRefresh(first);
        String second = refreshCookie(refreshed);

        assertThat(refreshed).hasStatusOk();
        assertThat(refreshed).bodyJson().extractingPath("$.accessToken").isNotNull();
        assertThat(second).isNotBlank().isNotEqualTo(first);
        assertThat(browserRefresh(second)).hasStatusOk();
    }

    /** Two tabs refreshing with the same cookie, without the app's cross-tab lock, look exactly like a stolen token. */
    @Test
    void replayingAnExchangedCookieEndsTheSessionAndClearsTheCookie() {
        String stolen = refreshCookie(browserLogin(username, PASSWORD));
        String next = refreshCookie(browserRefresh(stolen));

        MvcTestResult replay = browserRefresh(stolen);

        assertThat(replay).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(cookie(replay).getMaxAge()).isZero();
        assertThat(browserRefresh(next)).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void refreshWithoutACookieIsUnauthorized() {
        MvcTestResult result = mvc.post().uri("/api/v1/auth/browser/refresh").header(CSRF, "XMLHttpRequest").exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void signOutEndsTheSessionAndClearsTheCookie() {
        String token = refreshCookie(browserLogin(username, PASSWORD));

        MvcTestResult logout = mvc.post().uri("/api/v1/auth/browser/logout")
                .header(CSRF, "XMLHttpRequest")
                .cookie(new Cookie(COOKIE, token))
                .exchange();

        assertThat(logout).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(cookie(logout).getMaxAge()).isZero();
        assertThat(browserRefresh(token)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("""
                SELECT revoke_reason FROM auth_sessions s JOIN users u ON u.id = s.user_id WHERE u.username = ?
                """, String.class, username)).isEqualTo("LOGOUT");
    }

    /** A cross-site page can make the browser send the cookie, but not add a custom header without CORS approval. */
    @Test
    void everyEndpointRequiresTheAntiCsrfHeader() {
        String token = refreshCookie(browserLogin(username, PASSWORD));

        List<MvcTestResult> forged = List.of(
                mvc.post().uri("/api/v1/auth/browser/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, PASSWORD)).exchange(),
                mvc.post().uri("/api/v1/auth/browser/refresh")
                        .cookie(new Cookie(COOKIE, token)).exchange(),
                mvc.post().uri("/api/v1/auth/browser/logout")
                        .cookie(new Cookie(COOKIE, token)).exchange());

        assertThat(forged).allSatisfy(result -> assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("CSRF_CHECK_FAILED"));
        // Nothing happened: the session is still alive and its token was not exchanged.
        assertThat(browserRefresh(token)).hasStatusOk();
    }

    @Test
    void wrongPasswordIsRejectedWithoutACookie() {
        MvcTestResult result = browserLogin(username, "not the password at all");

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        assertThat(result.getMvcResult().getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    private MvcTestResult browserLogin(String username, String password) {
        return mvc.post().uri("/api/v1/auth/browser/login")
                .header(CSRF, "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password))
                .exchange();
    }

    private MvcTestResult browserRefresh(String cookieValue) {
        return mvc.post().uri("/api/v1/auth/browser/refresh")
                .header(CSRF, "XMLHttpRequest")
                .cookie(new Cookie(COOKIE, cookieValue))
                .exchange();
    }

    private static String credentials(String username, String password) {
        return """
                {"username": "%s", "password": "%s"}
                """.formatted(username, password);
    }

    private static String refreshCookie(MvcTestResult result) {
        return cookie(result).getValue();
    }

    private static HttpCookie cookie(MvcTestResult result) {
        String header = result.getMvcResult().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).as("Set-Cookie").isNotNull();
        HttpCookie cookie = HttpCookie.parse(header).getFirst();
        assertThat(cookie.getName()).isEqualTo(COOKIE);
        return cookie;
    }
}
