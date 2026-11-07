package com.flashmart.eureka;

import com.netflix.eureka.EurekaServerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server starts, and its registry endpoint answers over real HTTP. Also
 * pins the configuration the design depends on, read back from the live
 * server config: self-preservation off, and the library's default eviction
 * interval and response-cache interval untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EurekaServerApplicationTest {

    @LocalServerPort
    int port;

    @Autowired
    EurekaServerConfig serverConfig;

    @Test
    void theRegistryEndpointAnswersWithAnEmptyRegistry() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/eureka/apps"))
                        .header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"applications\"");
        // Standalone: it did not register itself.
        assertThat(r.body()).doesNotContainIgnoringCase("EUREKA-SERVER");
    }

    @Test
    void selfPreservationIsOffAndTimingsAreTheDefaults() {
        assertThat(serverConfig.shouldEnableSelfPreservation()).isFalse();
        assertThat(serverConfig.getEvictionIntervalTimerInMs()).isEqualTo(60_000L);
        assertThat(serverConfig.getResponseCacheUpdateIntervalMs()).isEqualTo(30_000L);
        assertThat(serverConfig.shouldUseReadOnlyResponseCache()).isTrue();
    }
}
