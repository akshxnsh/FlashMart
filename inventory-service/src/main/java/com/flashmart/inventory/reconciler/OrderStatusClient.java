package com.flashmart.inventory.reconciler;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Asks Order for an order's status: {@code GET /internal/orders/{id}/status}
 * (architecture.md §3, "Inventory → Order"). One attempt, no retries: the
 * reconciler's next pass is the retry. Connect and read timeouts from
 * {@link ReconcilerSettings} (1 s / 2 s, provisional).
 *
 * <p>Never throws: every outcome is an {@link Answer}, so the caller decides,
 * and an exception cannot slip past the "never guess" rule.
 */
@Component
public class OrderStatusClient {

    /** What Order said, or that it could not be asked. */
    public sealed interface Answer {
        /** 200 with a status (as Order spells it). */
        record Status(String status) implements Answer {
        }

        /** 404: Order has no such order. */
        record NotFound() implements Answer {
        }

        /** Timeout, refused, any other HTTP status, or a body that does not fit. */
        record Unreachable(String why) implements Answer {
        }
    }

    record Body(Long orderId, String status) {
    }

    private final RestClient client;

    public OrderStatusClient(ReconcilerSettings settings) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(settings.readTimeoutMs()));
        this.client = RestClient.builder().baseUrl(settings.orderBaseUrl()).requestFactory(factory).build();
    }

    public Answer statusOf(long orderId) {
        try {
            Body body = client.get().uri("/internal/orders/{id}/status", orderId).retrieve().body(Body.class);
            if (body == null || body.orderId() == null || body.orderId() != orderId || body.status() == null) {
                return new Answer.Unreachable("unexpected body " + body);
            }
            return new Answer.Status(body.status());
        } catch (HttpClientErrorException.NotFound e) {
            return new Answer.NotFound();
        } catch (RestClientException e) {
            return new Answer.Unreachable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
