package com.flashmart.gateway.error;

import org.springframework.web.server.WebExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;

/**
 * Every error the Gateway itself raises becomes {@code {code, message}}.
 * Ordered before Boot's default error handler (-1) and Spring's status handler (0).
 *
 * <ul>
 *   <li>No route matches (for example {@code /internal/**}, which is never
 *       routed): {@code 404 NOT_FOUND}.</li>
 *   <li>A route whose service has no instance, or whose fixed address refuses
 *       the connection: {@code 503 SERVICE_UNAVAILABLE}.</li>
 *   <li>Any other status keeps its number, with the status name as code.</li>
 *   <li>Anything else: {@code 500 INTERNAL_ERROR}.</li>
 * </ul>
 * Errors the downstream service answers itself pass through untouched: this
 * handler sees only exceptions, not downstream responses.
 */
@Component
@Order(-3)
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            HttpStatusCode code = rse.getStatusCode();
            if (code.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                return unavailable(exchange);
            }
            if (code.value() == HttpStatus.NOT_FOUND.value()) {
                return GatewayErrors.write(exchange.getResponse(), HttpStatus.NOT_FOUND, "NOT_FOUND", "no route for this path");
            }
            HttpStatus status = HttpStatus.resolve(code.value());
            if (status != null) {
                return GatewayErrors.write(exchange.getResponse(), status, status.name(), status.getReasonPhrase());
            }
        }
        if (causedBy(ex, ConnectException.class)) {
            return unavailable(exchange);
        }
        // Unexpected: log it, or a 500 is undiagnosable. Method, path and the
        // exception only; never headers (they carry the bearer token).
        log.error("unexpected gateway error on {} {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(), ex);
        return GatewayErrors.write(exchange.getResponse(), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "the gateway could not complete the request");
    }

    private static Mono<Void> unavailable(ServerWebExchange exchange) {
        return GatewayErrors.write(exchange.getResponse(), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "no instance of the target service is available");
    }

    private static boolean causedBy(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
