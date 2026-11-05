package com.flashmart.order.client.payment;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * Per-client Feign configuration for {@link PaymentGatewayClient}. Not a
 * {@code @Configuration}, so component scan does not apply it to every client.
 *
 * <p>Feign never retries: resilience4j does, in {@link PaymentCalls}, with a
 * fixed count that the CREATED deadline's payment budget is derived from. A
 * second retry layer here would multiply that worst case unseen.
 */
public class PaymentGatewayClientConfig {

    @Bean
    Retryer paymentGatewayRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
