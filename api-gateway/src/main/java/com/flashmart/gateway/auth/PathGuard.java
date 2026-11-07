package com.flashmart.gateway.auth;

import com.flashmart.gateway.error.GatewayErrors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Refuses, with {@code 400 INVALID_PATH}, any path that a downstream server
 * could resolve to a different path than the one the Gateway routed on.
 *
 * <p>Why: routes match by prefix, and the Gateway forwards the path as it came.
 * {@code /api/products/../../internal/flash-sale-items/reserve} matches the
 * Inventory route, and Inventory's Tomcat then normalises it to
 * {@code /internal/flash-sale-items/reserve}: the unauthenticated reserve
 * endpoint that must never be reachable through the Gateway. A test showed the
 * Gateway forwarding exactly that before this filter existed.
 *
 * <p>Refused: a {@code .} or {@code ..} segment (also percent-encoded, and also
 * with a {@code ;parameter}, which Tomcat strips before resolving), and an
 * encoded {@code /} or {@code \} ({@code %2F}, {@code %5C}) or a raw
 * {@code \}. No legitimate FlashMart path contains any of these.
 *
 * <p>A {@link WebFilter}, not a route filter, so it runs for every request,
 * routed or not, before the identity step.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PathGuard implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String raw = exchange.getRequest().getURI().getRawPath();
        if (raw != null && !isSafe(raw)) {
            return GatewayErrors.write(exchange.getResponse(), HttpStatus.BAD_REQUEST, "INVALID_PATH",
                    "the path contains dot segments or encoded separators");
        }
        return chain.filter(exchange);
    }

    static boolean isSafe(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c") || rawPath.indexOf('\\') >= 0) {
            return false;
        }
        String decoded;
        try {
            decoded = UriUtils.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException badEncoding) {
            return false;
        }
        if (decoded.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : decoded.split("/", -1)) {
            int semicolon = segment.indexOf(';');
            String name = semicolon >= 0 ? segment.substring(0, semicolon) : segment;
            if (name.equals(".") || name.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
