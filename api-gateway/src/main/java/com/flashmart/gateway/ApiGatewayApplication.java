package com.flashmart.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    /** The Gateway's clock: token expiry and X-Auth-Timestamp. A bean so tests can fix it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
