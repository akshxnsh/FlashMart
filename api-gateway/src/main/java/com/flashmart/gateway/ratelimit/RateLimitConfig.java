package com.flashmart.gateway.ratelimit;

import com.flashmart.gateway.auth.AuthFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * The Redis rate limiter bean and the keys of the two limiters (decisions.md,
 * "Day 8", point 7).
 *
 * <p>An empty key makes RequestRateLimiter answer 403 (deny-empty-key, its
 * default), so a request that somehow has no key is refused, never unlimited.
 */
@Configuration
public class RateLimitConfig {

    /**
     * Our copy of Spring Cloud Gateway's token-bucket script. Must stay identical to
     * {@code META-INF/scripts/request_rate_limiter.lua} in the Gateway library;
     * RateLimiterScriptTest fails on any difference (a library upgrade).
     */
    static final String SCRIPT_PATH = "ratelimit/request_rate_limiter.lua";

    /**
     * The Redis rate limiter, built exactly as Spring Cloud Gateway builds it
     * (same class, same constructor, same template and configuration service),
     * except for the script object (decisions.md, "Day 9: the Gateway's own
     * rate-limiter script").
     *
     * <p>Why the limiter and not just the script: the library's script bean
     * {@code redisRequestRateLimiterScript} has no "back off if present"
     * condition, and bean overriding is off, so it cannot be replaced. Its limiter
     * bean {@code redisRateLimiter} IS {@code @ConditionalOnMissingBean}, so this
     * bean makes it back off. The library's script bean is still created but
     * nothing calls it.
     *
     * <p>This 3-argument constructor marks the limiter initialized, so its
     * {@code setApplicationContext} does not look the library's script up by
     * name and replace ours. RateLimitTest checks the limiter in the context
     * holds this script. Rates, burst, keys and fail-open are unchanged: they
     * live in the routes and in {@link RedisRateLimiter} itself.
     */
    @Bean
    RedisRateLimiter redisRateLimiter(ReactiveStringRedisTemplate redisTemplate,
                                      ConfigurationService configurationService) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        RedisScript<List<Long>> script = (RedisScript) PreloadedRedisScript.fromClasspath(SCRIPT_PATH, List.class);
        return new RedisRateLimiter(redisTemplate, script, configurationService);
    }

    /**
     * The authenticated buyer id. It is read from {@code X-Buyer-Id}, which at
     * this point is the one {@link AuthFilter} set from the verified token: it
     * stripped any client copy first, and it runs BEFORE the limiter (global
     * filter order, proven by RateLimitTest). If the order were reversed, this
     * would read a header the client chose.
     */
    @Bean
    KeyResolver buyerKeyResolver() {
        return exchange -> {
            String buyer = exchange.getRequest().getHeaders().getFirst(AuthFilter.BUYER_ID);
            return buyer == null || buyer.isBlank() ? Mono.empty() : Mono.just("buyer:" + buyer);
        };
    }

    /**
     * The client's socket address. Never {@code X-Forwarded-For} or
     * {@code Forwarded}: a client could send any value and get a fresh bucket
     * per request. Behind a trusted proxy this would need changing; there is
     * none today. Accepted: everyone behind one NAT shares a bucket.
     */
    @Bean
    @Primary // Spring Cloud Gateway needs one default resolver; every route here names its own.
    KeyResolver ipKeyResolver() {
        return exchange -> {
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            return remote == null || remote.getAddress() == null
                    ? Mono.empty()
                    : Mono.just("ip:" + remote.getAddress().getHostAddress());
        };
    }
}
