package com.flashmart.paymentstub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A local stand-in for a payment gateway, in its own process so Order's real
 * connect/read timeouts, retries and breaker are exercised over real HTTP.
 * It is NOT the Razorpay Clone and verifies none of that project's behaviour.
 * In-memory only: a restart forgets every payment.
 */
@SpringBootApplication
public class PaymentStubApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentStubApplication.class, args);
    }
}
