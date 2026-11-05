package com.flashmart.order.client.payment;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * The payment gateway's initiate endpoint. Timeouts come from
 * {@code spring.cloud.openfeign.client.config.payment-gateway}, which reads
 * {@code payment.*}; Feign itself never retries ({@link PaymentGatewayClientConfig}).
 *
 * <p>Use {@link PaymentCalls}, not this interface: it adds the retry and the
 * circuit breaker and maps every failure to one of two exceptions.
 */
@FeignClient(name = "payment-gateway", url = "${payment-gateway.base-url}",
        configuration = PaymentGatewayClientConfig.class)
public interface PaymentGatewayClient {

    @PostMapping("/api/payments/initiate")
    InitiatePaymentResponse initiate(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @RequestBody InitiatePaymentRequest request);
}
