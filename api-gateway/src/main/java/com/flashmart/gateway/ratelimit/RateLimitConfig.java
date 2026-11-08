package com.flashmart.gateway.ratelimit;

import com.flashmart.gateway.auth.AuthFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * The keys of the two limiters (decisions.md, "Day 8", point 7).
 *
 * <p>An empty key makes RequestRateLimiter answer 403 (deny-empty-key, its
 * default), so a request that somehow has no key is refused, never unlimited.
 */
@Configuration
public class RateLimitConfig {

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
