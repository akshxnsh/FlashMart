package com.flashmart.gateway.ratelimit;

import com.flashmart.gateway.error.GatewayErrors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gives the limiter's bare 429 the project's body, {@code {code, message}}
 * with {@code code = RATE_LIMITED}, plus {@code Retry-After}.
 *
 * <p>RequestRateLimiter answers a denied request with status 429 and its
 * {@code X-RateLimit-*} headers, then completes with no body. This wraps the
 * response so that completion writes the body. {@code Retry-After} is in whole
 * seconds: {@code ceil(requested tokens / replenish rate)}, both read from the
 * limiter's own headers, i.e. the time for one token to come back (an estimate
 * of when a retry can succeed, not a promise).
 *
 * <p>Only a 429 that nothing wrote yet is touched; every other response passes
 * through the wrapper unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitResponseFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse original = exchange.getResponse();
        ServerHttpResponse wrapped = new ServerHttpResponseDecorator(original) {
            @Override
            public Mono<Void> setComplete() {
                if (HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode()) && !isCommitted()) {
                    getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(getHeaders())));
                    return GatewayErrors.write(getDelegate(), HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                            "too many requests; retry after the Retry-After seconds");
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(wrapped).build());
    }

    static long retryAfterSeconds(HttpHeaders headers) {
        try {
            double replenish = Double.parseDouble(headers.getFirst("X-RateLimit-Replenish-Rate"));
            double requested = Double.parseDouble(headers.getFirst("X-RateLimit-Requested-Tokens"));
            return Math.max(1, (long) Math.ceil(requested / replenish));
        } catch (RuntimeException headersMissing) {
            return 1;
        }
    }
}
