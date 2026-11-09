package com.flashmart.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Gateway must never forward {@code /actuator/**} to a service (decisions.md,
 * Day 9, Part B): a service's {@code /actuator/env} would otherwise be reachable
 * from outside, past the JWT check's intent. Nor {@code /internal/**}
 * (CLAUDE.md: internal endpoints are never routed through the Gateway): Inventory's
 * reserve and Order's order-status lookup for the stale-reservation reconciler
 * have no authentication of their own. Each route's own predicate is evaluated
 * against those paths, for every method; none may match. Done for the default
 * {@code lb://} routes and for the {@code fixed} profile's routes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ActuatorNotRoutedTest {

    private static final List<String> PATHS = List.of(
            "/actuator", "/actuator/health", "/actuator/prometheus", "/actuator/env",
            "/actuator/env/payment.read-timeout-ms",
            "/internal", "/internal/orders/1/status", "/internal/flash-sale-items/reserve");
    private static final List<HttpMethod> METHODS = List.of(HttpMethod.GET, HttpMethod.POST);

    @Autowired
    RouteLocator routeLocator;

    @Test
    void noRouteMatchesAnActuatorOrInternalPath() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).as("the routes are loaded").hasSizeGreaterThanOrEqualTo(4);
        for (Route route : routes) {
            for (String path : PATHS) {
                for (HttpMethod method : METHODS) {
                    MockServerWebExchange exchange = MockServerWebExchange.from(
                            MockServerHttpRequest.method(method, path).build());
                    Boolean matches = Mono.from(route.getPredicate().apply(exchange)).block();
                    assertThat(matches).as("route %s, %s %s", route.getId(), method, path).isFalse();
                }
            }
        }
    }

    @Test
    void theCheckCanSeeAMatch() {
        // Guard against a test that can never fail: a real route does match its own path.
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/flash-sales").build());
        boolean any = routes.stream()
                .anyMatch(r -> Boolean.TRUE.equals(Mono.from(r.getPredicate().apply(exchange)).block()));
        assertThat(any).isTrue();
    }
}
