package com.payledger.security.ratelimit;

import com.payledger.security.ratelimit.RateLimitProperties.Policy;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Token buckets kept in Redis, so every instance counts against the same limit: Stripe's "request rate limiter",
 * which also uses the token bucket algorithm on Redis. A bucket holds up to {@code capacity} tokens and refills
 * continuously; each request takes one. Short bursts pass, a sustained flood is cut to the refill rate.
 *
 * <p><b>Fails open.</b> Following Stripe's advice, a broken limiter (Redis down, slow or misconfigured) must never
 * stop the API: the request goes through unlimited. Commands time out after {@code redis-timeout}, and after a
 * failure Redis is not asked again for {@code fail-open-cooldown}, so an outage costs one timeout, not one per
 * request. Brute force on passwords is still stopped by the account lockout, which lives in PostgreSQL.
 *
 * <p>Uses its own Lettuce client rather than Spring's, for those timeouts and to reject commands at once while
 * disconnected instead of queueing them.
 */
@Component
public class RateLimiter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final String KEY_PREFIX = "payledger:rate-limit:";

    public sealed interface Decision permits Allowed, Rejected, Bypassed {
    }

    public record Allowed(long limit, long remaining) implements Decision {
    }

    public record Rejected(long limit, Duration retryAfter) implements Decision {
    }

    /** The limiter could not be asked, so the request is let through. */
    public record Bypassed() implements Decision {
    }

    private final RedisClient client;
    private final Duration timeout;
    private final long cooldownNanos;
    private final MeterRegistry meters;
    private final Object connectLock = new Object();
    private volatile StatefulRedisConnection<String, byte[]> connection;
    private volatile ProxyManager<String> buckets;
    private volatile long bypassUntilNanos = System.nanoTime();

    RateLimiter(DataRedisConnectionDetails redis, RateLimitProperties properties, MeterRegistry meters) {
        this.timeout = properties.redisTimeout();
        this.cooldownNanos = properties.failOpenCooldown().toNanos();
        this.meters = meters;
        this.client = RedisClient.create(redisUri(redis, timeout));
        this.client.setOptions(ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
    }

    /**
     * Takes one token from the bucket of {@code key}.
     *
     * @param policyName for metrics only, e.g. {@code user}
     */
    public Decision tryConsume(String key, String policyName, Policy policy) {
        if (System.nanoTime() - bypassUntilNanos < 0) {
            return count(new Bypassed(), policyName);
        }
        try {
            ConsumptionProbe probe = buckets().builder()
                    .build(KEY_PREFIX + key, () -> configuration(policy))
                    .tryConsumeAndReturnRemaining(1);
            Decision decision = probe.isConsumed()
                    ? new Allowed(policy.capacity(), probe.getRemainingTokens())
                    : new Rejected(policy.capacity(), Duration.ofNanos(probe.getNanosToWaitForRefill()));
            return count(decision, policyName);
        } catch (RuntimeException e) {
            bypassUntilNanos = System.nanoTime() + cooldownNanos;
            log.warn("Rate limiter unavailable, letting requests through for the next {} ms: {}",
                    cooldownNanos / 1_000_000, e.toString());
            return count(new Bypassed(), policyName);
        }
    }

    private static BucketConfiguration configuration(Policy policy) {
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(policy.capacity()).refillGreedy(policy.capacity(), policy.period()))
                .build();
    }

    // Connects on first use, so the application starts (unlimited) even if Redis is down at that moment.
    private ProxyManager<String> buckets() {
        ProxyManager<String> current = buckets;
        if (current != null) {
            return current;
        }
        synchronized (connectLock) {
            if (buckets == null) {
                connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                buckets = Bucket4jLettuce.casBasedBuilder(connection)
                        // A key lives until its bucket would be full again, so idle clients cost no memory.
                        .expirationAfterWrite(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                        .requestTimeout(timeout)
                        .build();
            }
            return buckets;
        }
    }

    private Decision count(Decision decision, String policyName) {
        String outcome = switch (decision) {
            case Allowed allowed -> "allowed";
            case Rejected rejected -> "rejected";
            case Bypassed bypassed -> "bypassed";
        };
        meters.counter("payledger.rate.limit.requests", "policy", policyName, "outcome", outcome).increment();
        return decision;
    }

    private static RedisURI redisUri(DataRedisConnectionDetails redis, Duration timeout) {
        DataRedisConnectionDetails.Standalone standalone = redis.getStandalone();
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(standalone.getHost())
                .withPort(standalone.getPort())
                .withDatabase(standalone.getDatabase())
                .withTimeout(timeout);
        if (redis.getPassword() != null) {
            uri = redis.getUsername() != null
                    ? uri.withAuthentication(redis.getUsername(), redis.getPassword())
                    : uri.withPassword(redis.getPassword());
        }
        return uri.build();
    }

    @Override
    public void destroy() {
        if (connection != null) {
            connection.close();
        }
        client.shutdown();
    }
}
