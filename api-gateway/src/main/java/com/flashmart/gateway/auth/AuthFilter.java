package com.flashmart.gateway.auth;

import com.flashmart.gateway.error.GatewayErrors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;

/**
 * The Gateway's identity step (decisions.md, "Day 8", points 2 and 5), on
 * every routed request, before routing:
 *
 * <ol>
 *   <li><b>Strip</b> any client-sent {@code X-Buyer-Id}, {@code X-Auth-Timestamp}
 *       and {@code X-Auth-Signature}, on every route, exempt ones and the
 *       webhook included. Only the Gateway may set them.</li>
 *   <li><b>Exempt</b> routes (signup, login, refresh, logout, the payment
 *       webhook; {@code POST} only) go on with no token and no identity
 *       headers.</li>
 *   <li>Every other request needs {@code Authorization: Bearer <token>} that
 *       {@link JwtVerifier} accepts, else {@code 401 UNAUTHORIZED}.</li>
 *   <li>On {@code /api/orders/**} the token must be {@code typ = user}; a valid
 *       token of another type is {@code 403 FORBIDDEN} (the caller is known but
 *       not allowed here; brand 7 and user 7 share an id).</li>
 *   <li><b>Sign</b>: set {@code X-Buyer-Id = sub}, {@code X-Auth-Timestamp} =
 *       epoch seconds on the Gateway clock, and {@code X-Auth-Signature} =
 *       {@link BuyerHeaderSigner#sign}.</li>
 * </ol>
 *
 * Paths are matched with {@link PathPattern}, the same matcher the route
 * predicates use, on the DECODED path: a raw string compare could be skipped
 * with {@code /api/%6Frders}, which the route would still send to Order.
 * Nothing here blocks or does I/O, and no token or secret is ever logged.
 */
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    public static final String BUYER_ID = "X-Buyer-Id";
    public static final String TIMESTAMP = "X-Auth-Timestamp";
    public static final String SIGNATURE = "X-Auth-Signature";
    static final String USER_TYPE = "user";
    private static final String BEARER = "Bearer ";

    /** Before every Spring Cloud Gateway filter, including the load balancer and routing ones. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;
    private static final List<PathPattern> EXEMPT_POST = List.of(
            PARSER.parse("/api/auth/signup"),
            PARSER.parse("/api/auth/login"),
            PARSER.parse("/api/auth/refresh"),
            PARSER.parse("/api/auth/logout"),
            PARSER.parse("/webhooks/payment"));
    private static final PathPattern ORDERS = PARSER.parse("/api/orders/**");

    private final JwtVerifier jwt;
    private final BuyerHeaderSigner signer;
    private final Clock clock;

    public AuthFilter(JwtVerifier jwt, BuyerHeaderSigner signer, Clock clock) {
        this.jwt = jwt;
        this.signer = signer;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(BUYER_ID);
                    h.remove(TIMESTAMP);
                    h.remove(SIGNATURE);
                })
                .build();
        PathContainer path = stripped.getPath().pathWithinApplication();

        if (HttpMethod.POST.equals(stripped.getMethod()) && matchesAny(EXEMPT_POST, path)) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        String authorization = stripped.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return GatewayErrors.write(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "a Bearer access token is required");
        }
        JwtVerifier.VerifiedToken token;
        try {
            token = jwt.verify(authorization.substring(BEARER.length()).trim());
        } catch (JwtVerifier.InvalidTokenException e) {
            return GatewayErrors.write(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    e.getMessage());
        }
        if (ORDERS.matches(path) && !USER_TYPE.equals(token.type())) {
            return GatewayErrors.write(exchange.getResponse(), HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "this token type may not place or read orders");
        }

        long now = clock.instant().getEpochSecond();
        String signature = signer.sign(token.subject(), now);
        ServerHttpRequest signed = stripped.mutate()
                .headers(h -> {
                    h.set(BUYER_ID, token.subject());
                    h.set(TIMESTAMP, Long.toString(now));
                    h.set(SIGNATURE, signature);
                })
                .build();
        return chain.filter(exchange.mutate().request(signed).build());
    }

    private static boolean matchesAny(List<PathPattern> patterns, PathContainer path) {
        for (PathPattern p : patterns) {
            if (p.matches(path)) {
                return true;
            }
        }
        return false;
    }
}
