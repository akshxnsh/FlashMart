package com.flashmart.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metrics are exposed on the service's own port (decisions.md, Day 9, Part B):
 * {@code /actuator/prometheus} answers and carries the HTTP server request
 * HISTOGRAM (the {@code _bucket} lines Prometheus computes p99 from). Only
 * health, prometheus and env are exposed. Real HTTP; no Prometheus needed. The
 * export is switched on here because Boot turns it off in tests by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.prometheus.metrics.export.enabled=true")
class PrometheusEndpointTest {

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void prometheusEndpointCarriesTheHttpServerRequestHistogram() throws Exception {
        get("/actuator/health"); // one request, so a request timer exists

        HttpResponse<String> r = get("/actuator/prometheus");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("http_server_requests_seconds_bucket");
        // The payment breaker's state is exported (resilience4j-micrometer).
        assertThat(r.body()).contains("resilience4j_circuitbreaker_state").contains("name=\"payment-gateway\"");
    }

    @Test
    void onlyHealthPrometheusAndEnvAreExposed() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/env").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/beans").statusCode()).isEqualTo(404);
        assertThat(get("/actuator/metrics").statusCode()).isEqualTo(404);
    }
}
