package com.payledger.security.token;

import com.nimbusds.jwt.SignedJWT;
import com.payledger.security.Secrets;
import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenApiIntegrationTest extends ApiTestSupport {

    @Autowired
    Sessions sessions;

    private String username;
    private String userId;

    @BeforeEach
    void signUpCustomer() {
        username = uniqueUsername();
        userId = jsonPath(signUp(username, PASSWORD), "$.id");
    }

    @Test
    void eachRefreshRotatesTheTokenWithinTheSameSession() throws Exception {
        MvcTestResult login = login(username, PASSWORD);
        String first = jsonPath(login, "$.refreshToken");

        MvcTestResult refreshed = refresh(first);
        String second = jsonPath(refreshed, "$.refreshToken");

        assertThat(refreshed).hasStatusOk();
        assertThat(second).isNotEqualTo(first);
        assertThat(sid(refreshed)).isEqualTo(sid(login));
        assertThat(SignedJWT.parse(jsonPath(refreshed, "$.accessToken")).getJWTClaimsSet().getSubject())
                .isEqualTo(userId);
        assertThat(refresh(second)).hasStatusOk();
    }

    @Test
    void storesOnlyAHashOfTheRefreshToken() {
        String token = jsonPath(login(username, PASSWORD), "$.refreshToken");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Long.class,
                Secrets.sha256(token))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Long.class, token))
                .isZero();
    }

    /**
     * The attacker copied the token and the customer refreshed with it first (or the other way round). When the
     * exchanged token comes back, the server cannot tell who is who, so it ends the session for both.
     */
    @Test
    void replayingAnExchangedTokenRevokesTheWholeSession() {
        String stolen = jsonPath(login(username, PASSWORD), "$.refreshToken");
        String customersNext = jsonPath(refresh(stolen), "$.refreshToken");

        MvcTestResult replay = refresh(stolen);

        assertThat(replay).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(replay).bodyJson().extractingPath("$.code").isEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(refresh(customersNext))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(jdbc.queryForObject("SELECT revoke_reason FROM auth_sessions WHERE user_id = ?::uuid", String.class,
                userId)).isEqualTo("REFRESH_TOKEN_REUSE");
    }

    /** Ten requests race with one token: it is exchanged exactly once, and the race itself counts as reuse. */
    @Test
    void parallelRefreshesWithOneTokenSucceedOnlyOnce() throws Exception {
        String token = jsonPath(login(username, PASSWORD), "$.refreshToken");
        int requests = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcTestResult>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return refresh(token);
                }));
            }
            start.countDown();
        }

        Map<Integer, Integer> statuses = new HashMap<>();
        String winnersToken = null;
        for (Future<MvcTestResult> result : results) {
            MvcTestResult response = result.get();
            int status = response.getMvcResult().getResponse().getStatus();
            statuses.merge(status, 1, Integer::sum);
            if (status == 200) {
                winnersToken = jsonPath(response, "$.refreshToken");
            }
        }
        assertThat(statuses).containsExactlyInAnyOrderEntriesOf(Map.of(200, 1, 401, requests - 1));
        assertThat(refresh(winnersToken)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutEndsTheSession() {
        String token = jsonPath(login(username, PASSWORD), "$.refreshToken");

        assertThat(logout(token)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(refresh(token))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(jdbc.queryForObject("SELECT revoke_reason FROM auth_sessions WHERE user_id = ?::uuid", String.class,
                userId)).isEqualTo("LOGOUT");
    }

    @Test
    void logoutWithAnUnknownTokenStillSucceeds() {
        assertThat(logout("not-a-real-token")).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void anIdleTokenExpires() {
        String token = jsonPath(login(username, PASSWORD), "$.refreshToken");
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 second' WHERE token_hash = ?",
                Secrets.sha256(token));

        assertThat(refresh(token))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void aSessionEndsAtItsAbsoluteLifetimeAndNoTokenOutlivesIt() {
        String token = jsonPath(login(username, PASSWORD), "$.refreshToken");
        jdbc.update("UPDATE auth_sessions SET expires_at = now() + interval '60 seconds' WHERE user_id = ?::uuid",
                userId);

        MvcTestResult refreshed = refresh(token);
        assertThat(refreshed).hasStatusOk();
        assertThat(ApiTestSupport.<Integer>jsonPath(refreshed, "$.refreshExpiresIn")).isBetween(59, 60);

        jdbc.update("UPDATE auth_sessions SET expires_at = now() - interval '1 second' WHERE user_id = ?::uuid",
                userId);
        assertThat(refresh(jsonPath(refreshed, "$.refreshToken"))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRoleChangeTakesEffectAtTheNextRefresh() throws Exception {
        String token = jsonPath(login(username, PASSWORD), "$.refreshToken");
        jdbc.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?::uuid", userId);

        MvcTestResult refreshed = refresh(token);

        assertThat(SignedJWT.parse(jsonPath(refreshed, "$.accessToken")).getJWTClaimsSet().getStringListClaim("roles"))
                .containsExactly("OPERATOR");
    }

    @Test
    void cleanupDeletesExpiredSessionsWithTheirTokens() {
        String token = jsonPath(login(username, PASSWORD), "$.refreshToken");
        jdbc.update("UPDATE auth_sessions SET expires_at = now() - interval '1 second' WHERE user_id = ?::uuid",
                userId);

        while (sessions.deleteExpired(1_000) == 1_000) {
            // keep deleting
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id = ?::uuid", Long.class,
                userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Long.class,
                Secrets.sha256(token))).isZero();
    }

    private MvcTestResult refresh(String refreshToken) {
        return post("/api/v1/auth/refresh", refreshToken);
    }

    private MvcTestResult logout(String refreshToken) {
        return post("/api/v1/auth/logout", refreshToken);
    }

    private MvcTestResult post(String uri, String refreshToken) {
        return mvc.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken": "%s"}
                        """.formatted(refreshToken))
                .exchange();
    }

    private static String sid(MvcTestResult tokenResponse) throws Exception {
        return SignedJWT.parse(jsonPath(tokenResponse, "$.accessToken")).getJWTClaimsSet().getStringClaim("sid");
    }
}
