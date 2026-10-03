package com.payledger.security.ratelimit;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Small buckets, so the limits are reached within a test. Runs in its own context, with its own Redis. */
@TestPropertySource(properties = {
        "payledger.rate-limit.policies.user.capacity=3",
        "payledger.rate-limit.policies.user.period=1m",
        "payledger.rate-limit.policies.anonymous.capacity=3",
        "payledger.rate-limit.policies.anonymous.period=1m",
        "payledger.rate-limit.policies.api-key.capacity=2",
        "payledger.rate-limit.policies.api-key.period=1s",
        "payledger.rate-limit.fail-open-cooldown=1s"})
class RateLimitIntegrationTest extends ApiTestSupport {

    @Autowired
    @Qualifier("redis")
    GenericContainer<?> redis;

    @Test
    void aUserSeesTheLimitAndGets429BeyondIt() {
        String customer = newCustomer();

        for (int remaining = 2; remaining >= 0; remaining--) {
            MvcTestResult allowed = listAccounts(customer);
            assertThat(allowed).hasStatusOk();
            assertThat(allowed).headers().hasValue("X-RateLimit-Limit", "3");
            assertThat(allowed).headers().hasValue("X-RateLimit-Remaining", Integer.toString(remaining));
        }
        MvcTestResult limited = listAccounts(customer);

        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(limited).bodyJson().extractingPath("$.code").isEqualTo("RATE_LIMITED");
        assertThat(limited).headers().hasValue("X-RateLimit-Remaining", "0");
        // 3 per minute: the next token arrives within 20 seconds.
        assertThat(Long.parseLong(limited.getMvcResult().getResponse().getHeader("Retry-After"))).isBetween(1L, 20L);
    }

    @Test
    void eachUserHasTheirOwnBucket() {
        String greedy = newCustomer();
        for (int i = 0; i < 4; i++) {
            listAccounts(greedy);
        }

        assertThat(listAccounts(greedy)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(listAccounts(newCustomer())).hasStatusOk();
    }

    /** Credential stuffing comes without credentials, so it is limited per address. */
    @Test
    void requestsWithoutCredentialsAreLimitedPerAddress() {
        for (int i = 0; i < 3; i++) {
            assertThat(loginFrom("203.0.113.7")).hasStatus(HttpStatus.UNAUTHORIZED);
        }

        assertThat(loginFrom("203.0.113.7")).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(loginFrom("203.0.113.8")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aBucketRefillsOverTime() throws Exception {
        // 2 per second: the bank's key is counted even where it is then refused (403).
        assertThat(asBankGet()).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(asBankGet()).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(asBankGet()).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        Thread.sleep(600);

        assertThat(asBankGet()).hasStatus(HttpStatus.FORBIDDEN);
    }

    /**
     * Redis freezes ({@code docker pause}): connections stay open, nothing answers. Requests must keep being served,
     * without limits and without waiting for Redis each time, and limiting must resume once Redis is back.
     */
    @Test
    void failsOpenWhileRedisIsDownAndRecoversAfterwards() {
        String customer = newCustomer();
        for (int i = 0; i < 3; i++) {
            listAccounts(customer);
        }
        assertThat(listAccounts(customer)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        DockerClientFactory.instance().client().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            for (int i = 0; i < 10; i++) {
                long start = System.nanoTime();
                MvcTestResult result = listAccounts(customer);
                Duration took = Duration.ofNanos(System.nanoTime() - start);

                assertThat(result).hasStatusOk();
                assertThat(result).headers().doesNotContainHeader("X-RateLimit-Limit");
                // One Redis timeout (200 ms) at most, then the cooldown skips Redis altogether.
                assertThat(took).isLessThan(Duration.ofSeconds(1));
            }
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(redis.getContainerId()).exec();
        }

        // The bucket survived in Redis: once it answers again, this customer is still out of tokens.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .until(() -> listAccounts(customer).getMvcResult().getResponse().getStatus() == 429);
    }

    @Test
    void probesAndMetricsAreNeverLimited() {
        for (int i = 0; i < 10; i++) {
            assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        }
    }

    private MvcTestResult listAccounts(String customer) {
        return mvc.get().uri("/api/v1/accounts").with(asCustomer(customer)).exchange();
    }

    private MvcTestResult asBankGet() {
        return mvc.get().uri("/api/v1/users/me").with(asBank()).exchange();
    }

    private MvcTestResult loginFrom(String address) {
        return mvc.post().uri("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "%s", "password": "not the password at all"}
                        """.formatted(uniqueUsername()))
                .exchange();
    }
}
